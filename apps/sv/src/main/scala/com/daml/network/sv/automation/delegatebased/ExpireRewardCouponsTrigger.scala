// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.delegatebased

import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.amuletrules.{
  AmuletRules,
  AmuletRules_ClaimExpiredRewards,
}
import com.daml.network.codegen.java.splice.dsorules.DsoRules
import com.daml.network.sv.store.ExpiredRewardCouponsBatch
import com.daml.network.util.{AssignedContract, Contract}
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class ExpireRewardCouponsTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[ExpiredRewardCouponsBatch]
    with SvTaskBasedTrigger[ExpiredRewardCouponsBatch] {
  private val store = svTaskContext.dsoStore

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[ExpiredRewardCouponsBatch]] = for {
    dsoRules <- store.getDsoRules()
    batches <- store.getExpiredRewards(dsoRules.domain, context.config.enableExpireValidatorFaucet)
  } yield batches

  override protected def isStaleTask(expiredRewardsTask: ExpiredRewardCouponsBatch)(implicit
      tc: TraceContext
  ): Future[Boolean] = store.multiDomainAcsStore.hasArchived(
    expiredRewardsTask.validatorCoupons ++ expiredRewardsTask.appCoupons
  )

  override def completeTaskAsDsoDelegate(
      expiredRewardsTask: ExpiredRewardCouponsBatch
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      numCoupons <- expireRewardCouponsForRound(
        expiredRewardsTask,
        dsoRules,
        amuletRules,
      )
    } yield TaskSuccess(
      show"Expired ${numCoupons} old reward coupons for closed round ${expiredRewardsTask}"
    )
  }

  private def expireRewardCouponsForRound(
      expiredRewardsTask: ExpiredRewardCouponsBatch,
      dsoRules: AssignedContract[DsoRules.ContractId, DsoRules],
      amuletRules: Contract[AmuletRules.ContractId, AmuletRules],
  )(implicit
      tc: TraceContext
  ): Future[Int] = {
    val validatorRewardCmd = Seq(
      dsoRules.exercise(
        _.exerciseDsoRules_ClaimExpiredRewards(
          amuletRules.contractId,
          new AmuletRules_ClaimExpiredRewards(
            expiredRewardsTask.closedRoundCid,
            expiredRewardsTask.validatorCoupons.asJava,
            Seq.empty.asJava,
            Seq.empty.asJava,
            None.toJava,
          ),
        )
      )
    ).filter(_ => expiredRewardsTask.validatorCoupons.nonEmpty)
    val validatorFaucetCmd = Seq(
      dsoRules.exercise(
        _.exerciseDsoRules_ClaimExpiredRewards(
          amuletRules.contractId,
          new AmuletRules_ClaimExpiredRewards(
            expiredRewardsTask.closedRoundCid,
            Seq.empty.asJava,
            Seq.empty.asJava,
            Seq.empty.asJava,
            Some(expiredRewardsTask.validatorFaucets.asJava).toJava,
          ),
        )
      )
    ).filter(_ => expiredRewardsTask.validatorFaucets.nonEmpty)
    val appRewardCmd = Seq(
      dsoRules.exercise(
        _.exerciseDsoRules_ClaimExpiredRewards(
          amuletRules.contractId,
          new AmuletRules_ClaimExpiredRewards(
            expiredRewardsTask.closedRoundCid,
            Seq.empty.asJava,
            expiredRewardsTask.appCoupons.asJava,
            Seq.empty.asJava,
            None.toJava,
          ),
        )
      )
    ).filter(_ => expiredRewardsTask.appCoupons.nonEmpty)
    val svRewardCmd = Seq(
      dsoRules.exercise(
        _.exerciseDsoRules_ClaimExpiredRewards(
          amuletRules.contractId,
          new AmuletRules_ClaimExpiredRewards(
            expiredRewardsTask.closedRoundCid,
            Seq.empty.asJava,
            Seq.empty.asJava,
            expiredRewardsTask.svRewardCoupons.asJava,
            None.toJava,
          ),
        )
      )
    ).filter(_ => expiredRewardsTask.svRewardCoupons.nonEmpty)
    val cmds = Seq(validatorRewardCmd, appRewardCmd, svRewardCmd, validatorFaucetCmd).flatten
    for {
      _ <- Future.sequence(
        cmds.map(cmd =>
          svTaskContext.connection
            .submit(
              Seq(store.key.svParty),
              Seq(store.key.dsoParty),
              cmd,
            )
            .noDedup
            .yieldResult()
        )
      )
    } yield expiredRewardsTask.validatorCoupons.size + expiredRewardsTask.appCoupons.size + expiredRewardsTask.validatorFaucets.size + expiredRewardsTask.svRewardCoupons.size
  }
}
