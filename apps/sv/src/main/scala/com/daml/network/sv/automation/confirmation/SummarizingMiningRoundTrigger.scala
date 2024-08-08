// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.confirmation

import org.apache.pekko.stream.Materializer
import cats.syntax.traverseFilter.*
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice
import com.daml.network.codegen.java.splice.amuletrules.AmuletRules_MiningRound_StartIssuing
import com.daml.network.codegen.java.splice.issuance.OpenMiningRoundSummary
import com.daml.network.codegen.java.splice.round.SummarizingMiningRound
import com.daml.network.codegen.java.splice.dsorules.ActionRequiringConfirmation
import com.daml.network.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import com.daml.network.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_MiningRound_StartIssuing
import com.daml.network.environment.SpliceLedgerConnection
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.store.SvDsoStore
import com.daml.network.util.AssignedContract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}

/** This is a polling trigger to avoid issues where SVs run out of retries (e.g. due to the synchronizer being down)
  * and then the round gets stuck forever in the summarizing state.
  */
class SummarizingMiningRoundTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[SummarizingMiningRoundTrigger.Task] {

  import SummarizingMiningRoundTrigger.*

  private val svParty = store.key.svParty
  private val dsoParty = store.key.dsoParty

  private def amuletRulesStartIssuingAction(
      miningRoundCid: SummarizingMiningRound.ContractId,
      summary: OpenMiningRoundSummary,
  ): ActionRequiringConfirmation =
    new ARC_AmuletRules(
      new CRARC_MiningRound_StartIssuing(
        new AmuletRules_MiningRound_StartIssuing(
          miningRoundCid,
          summary,
        )
      )
    )

  override def retrieveTasks()(implicit tc: TraceContext): Future[Seq[Task]] = for {
    summarizingRounds <- store.listOldestSummarizingMiningRounds()
    tasks <- summarizingRounds.traverseFilter { round =>
      for {
        rewards <- queryRewards(round.payload.round.number, round.domain)
        action = amuletRulesStartIssuingAction(
          round.contractId,
          rewards.summary,
        )
        queryResult <- store.lookupConfirmationByActionWithOffset(svParty, action)
      } yield queryResult.value match {
        case None =>
          Some(
            Task(
              round,
              rewards,
            )
          )
        case Some(_) => None
      }
    }
  } yield tasks

  override def completeTask(
      task: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      action = amuletRulesStartIssuingAction(
        task.summarizingRound.contractId,
        task.rewards.summary,
      )
      queryResult <- store.lookupConfirmationByActionWithOffset(svParty, action)
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_ConfirmAction(
          svParty.toProtoPrimitive,
          action,
        )
      )
      taskOutcome <- queryResult match {
        case QueryResult(_, Some(_)) =>
          Future.successful(
            TaskSuccess(
              s"skipping as confirmation from ${svParty} is already created for such action"
            )
          )
        case QueryResult(offset, None) =>
          connection
            .submit(
              actAs = Seq(svParty),
              readAs = Seq(dsoParty),
              update = cmd,
            )
            .withDedup(
              commandId = SpliceLedgerConnection.CommandId(
                "com.daml.network.sv.createMiningRoundStartIssuingConfirmation",
                Seq(svParty, dsoParty),
                task.summarizingRound.contractId.contractId,
              ),
              deduplicationOffset = offset,
            )
            .yieldUnit()
            .map { _ =>
              TaskSuccess(
                s"created confirmation for summarizing mining round"
              )
            }
      }
    } yield taskOutcome
  }

  override def isStaleTask(task: SummarizingMiningRoundTrigger.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    // We don't bother checking if a confirmation exists since this is handled in completeTask
    store.multiDomainAcsStore
      .lookupContractById(SummarizingMiningRound.COMPANION)(task.summarizingRound.contractId)
      .map(_.isEmpty)

  /** Query the open reward contracts for a given round. This should only be used
    * for a SummarizingMiningRound.
    */
  private def queryRewards(round: Long, domain: DomainId)(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[RoundRewards] = {
    for {
      appRewardCoupons <- store.sumAppRewardCouponsOnDomain(
        round,
        domain,
      )
      validatorRewardCoupons <- store.sumValidatorRewardCouponsOnDomain(
        round,
        domain,
      )
      validatorFaucetCoupons <- store.countValidatorFaucetCouponsOnDomain(
        round,
        domain,
      )
      svRewardCouponsWeightSum <- store.sumSvRewardCouponWeightsOnDomain(
        round,
        domain,
      )
    } yield {
      RoundRewards(
        round = round,
        featuredAppRewardCoupons = appRewardCoupons.featured,
        unfeaturedAppRewardCoupons = appRewardCoupons.unfeatured,
        validatorRewardCoupons = validatorRewardCoupons,
        validatorFaucetCoupons = validatorFaucetCoupons,
        svRewardCouponsWeightSum = svRewardCouponsWeightSum,
      )
    }
  }
}

object SummarizingMiningRoundTrigger {
  final case class RoundRewards(
      round: Long,
      featuredAppRewardCoupons: BigDecimal,
      unfeaturedAppRewardCoupons: BigDecimal,
      validatorRewardCoupons: BigDecimal,
      validatorFaucetCoupons: Long,
      svRewardCouponsWeightSum: Long,
  ) extends PrettyPrinting {
    lazy val summary: splice.issuance.OpenMiningRoundSummary =
      new splice.issuance.OpenMiningRoundSummary(
        validatorRewardCoupons.bigDecimal,
        featuredAppRewardCoupons.bigDecimal,
        unfeaturedAppRewardCoupons.bigDecimal,
        svRewardCouponsWeightSum,
        Optional.of(validatorFaucetCoupons),
      )

    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("featuredAppRewardCoupons", _.featuredAppRewardCoupons),
        param("unfeaturedAppRewardCoupons", _.unfeaturedAppRewardCoupons),
        param("validatorRewardCoupons", _.validatorRewardCoupons),
        param("validatorFaucetCoupons", _.validatorFaucetCoupons),
        param("svRewardCouponsWeightSum", _.svRewardCouponsWeightSum),
      )
  }

  final case class Task(
      summarizingRound: AssignedContract[
        splice.round.SummarizingMiningRound.ContractId,
        splice.round.SummarizingMiningRound,
      ],
      rewards: RoundRewards,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("summarizingRound", _.summarizingRound),
        param("rewards", _.rewards),
      )
  }
}
