// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation

import com.daml.network.automation.{
  AmuletConfigReassignmentTrigger,
  AssignTrigger,
  AutomationServiceCompanion,
  SpliceAppAutomationService,
  TransferFollowTrigger,
}
import com.daml.network.automation.AutomationServiceCompanion.{TriggerClass, aTrigger}
import com.daml.network.codegen.java.splice.amuletrules.AmuletRules
import com.daml.network.codegen.java.splice.dsorules.DsoRules
import com.daml.network.codegen.java.splice.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.config.UpgradesConfig
import com.daml.network.environment.*
import com.daml.network.http.HttpClient
import com.daml.network.store.{DomainTimeSynchronization, DomainUnpausedSynchronization}
import com.daml.network.store.MultiDomainAcsStore.ConstrainedTemplate
import com.daml.network.sv.{ExtraSynchronizerNode, LocalSynchronizerNode}
import com.daml.network.sv.automation.SvDsoAutomationService.{
  LocalSequencerClientConfig,
  LocalSequencerClientContext,
}
import com.daml.network.sv.automation.confirmation.*
import com.daml.network.sv.automation.singlesv.*
import com.daml.network.sv.automation.singlesv.SvNamespaceMembershipTrigger
import com.daml.network.sv.automation.singlesv.offboarding.{
  SvOffboardingMediatorTrigger,
  SvOffboardingPartyToParticipantProposalTrigger,
  SvOffboardingSequencerTrigger,
}
import com.daml.network.sv.automation.singlesv.onboarding.*
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.{SequencerPruningConfig, SvAppBackendConfig}
import com.daml.network.sv.migration.DecentralizedSynchronizerMigrationTrigger
import com.daml.network.sv.store.{SvDsoStore, SvSvStore}
import com.daml.network.util.{QualifiedName, TemplateJsonDecoder}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.{Clock, WallClock}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import monocle.Monocle.toAppliedFocusOps
import org.apache.pekko.stream.Materializer

import java.nio.file.Path
import scala.concurrent.{ExecutionContextExecutor, Future}

class SvDsoAutomationService(
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
    config: SvAppBackendConfig,
    svStore: SvSvStore,
    dsoStore: SvDsoStore,
    ledgerClient: SpliceLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    cometBft: Option[CometBftNode],
    localSynchronizerNode: Option[LocalSynchronizerNode],
    extraSynchronizerNodes: Map[String, ExtraSynchronizerNode],
    upgradesConfig: UpgradesConfig,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
    httpClient: HttpClient,
    templateJsonDecoder: TemplateJsonDecoder,
) extends SpliceAppAutomationService(
      config.automation,
      clock,
      domainTimeSync,
      domainUnpausedSync,
      dsoStore,
      PackageIdResolver
        .inferFromAmuletRules(
          clock,
          dsoStore,
          loggerFactory,
          SvDsoAutomationService.bootstrapPackageIdResolver,
        ),
      ledgerClient,
      retryProvider,
      config.ingestFromParticipantBegin,
      config.ingestUpdateHistoryFromParticipantBegin,
    ) {

  override def companion = SvDsoAutomationService

  private[network] val restartDsoDelegateBasedAutomationTrigger =
    new RestartDsoDelegateBasedAutomationTrigger(
      triggerContext,
      domainTimeSync,
      domainUnpausedSync,
      dsoStore,
      connection,
      clock,
      config,
      retryProvider,
    )

  // required for triggers that must run in sim time as well
  private val wallClockTriggerContext = triggerContext
    .focus(_.clock)
    .replace(
      new WallClock(triggerContext.timeouts, triggerContext.loggerFactory)
    )

  private val onboardingTriggerContext = wallClockTriggerContext
    .focus(_.config.pollingInterval)
    .replace(
      config.onboardingPollingInterval.getOrElse(wallClockTriggerContext.config.pollingInterval)
    )

  // Trigger that starts only after the SV namespace is added to the decentralized namespace
  def registerSvNamespaceMembershipTrigger(): Unit = {
    registerTrigger(
      new SvNamespaceMembershipTrigger(
        onboardingTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
  }

  // Triggers that require namespace permissions and the existence of the DsoRules and AmuletRules contracts
  def registerPostOnboardingTriggers(): Unit = {
    registerTrigger(
      new SvOnboardingRequestTrigger(triggerContext, dsoStore, svStore, config, connection)
    )
    // Register optional BFT triggers
    cometBft.foreach { node =>
      if (triggerContext.config.enableCometbftReconciliation) {
        registerTrigger(
          new PublishLocalCometBftNodeConfigTrigger(
            triggerContext,
            dsoStore,
            connection,
            node,
          )
        )
        registerTrigger(
          new ReconcileCometBftNetworkConfigWithDsoRulesTrigger(
            triggerContext,
            dsoStore,
            node,
          )
        )
      }
    }
    registerTrigger(
      new SvOffboardingPartyToParticipantProposalTrigger(
        triggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOffboardingMediatorTrigger(
        wallClockTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOffboardingSequencerTrigger(
        wallClockTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOnboardingPromoteParticipantToSubmitterTrigger(
        onboardingTriggerContext,
        dsoStore,
        participantAdminConnection,
        config.enableOnboardingParticipantPromotionDelay,
      )
    )
    registerTrigger(
      new SvOnboardingPartyToParticipantProposalTrigger(
        onboardingTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOnboardingSequencerTrigger(
        onboardingTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOnboardingMediatorProposalTrigger(
        onboardingTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )

    (localSynchronizerNode, config.domainMigrationDumpPath) match {
      case (Some(synchronizerNode), Some(dumpPath)) =>
        registerTrigger(
          new DecentralizedSynchronizerMigrationTrigger(
            config.domainMigrationId,
            triggerContext,
            config.domains.global.alias,
            synchronizerNode,
            dsoStore,
            participantAdminConnection,
            synchronizerNode.sequencerAdminConnection,
            dumpPath: Path,
          )
        )
      case _ => ()
    }
    registerTrigger(
      new ReconcileDynamicDomainParametersTrigger(
        triggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
  }

  def registerPostSequencerInitTriggers(): Unit = {
    registerTrigger(
      new ReconcileSequencerLimitWithMemberTrafficTrigger(
        triggerContext,
        dsoStore,
        localSynchronizerNode.map(_.sequencerAdminConnection),
        extraSynchronizerNodes,
        config.trafficBalanceReconciliationDelay,
      )
    )
    registerTrigger(
      new SvOnboardingUnlimitedTrafficTrigger(
        onboardingTriggerContext,
        dsoStore,
        localSynchronizerNode.map(_.sequencerAdminConnection),
        extraSynchronizerNodes,
        config.trafficBalanceReconciliationDelay,
      )
    )
  }

  def registerPostUnlimitedTrafficTriggers(): Unit = {
    registerTrigger(new SummarizingMiningRoundTrigger(triggerContext, dsoStore, connection))
    registerTrigger(
      new ReceiveSvRewardCouponTrigger(
        triggerContext,
        dsoStore,
        connection,
        config.extraBeneficiaries,
      )
    )
    if (config.automation.enableClosedRoundArchival)
      registerTrigger(new ArchiveClosedMiningRoundsTrigger(triggerContext, dsoStore, connection))

    if (config.automation.enableDsoDelegateReplacementTrigger) {
      registerTrigger(new ElectionRequestTrigger(triggerContext, dsoStore, connection))
    }

    registerTrigger(restartDsoDelegateBasedAutomationTrigger)

    if (config.supportsSoftDomainMigrationPoc) {
      registerTrigger(
        new AmuletConfigReassignmentTrigger(
          triggerContext,
          dsoStore,
          connection,
          dsoStore.key.dsoParty,
          Seq[ConstrainedTemplate](
            AmuletRules.COMPANION,
            OpenMiningRound.COMPANION,
            IssuingMiningRound.COMPANION,
          ),
          (tc: TraceContext) => dsoStore.lookupAmuletRules()(tc),
        )
      )
    } else {
      registerTrigger(
        new AmuletConfigReassignmentTrigger(
          triggerContext,
          dsoStore,
          connection,
          dsoStore.key.dsoParty,
          Seq(DsoRules.COMPANION),
          (tc: TraceContext) => dsoStore.lookupAmuletRules()(tc),
        )
      )
      registerTrigger(
        new TransferFollowTrigger(
          triggerContext,
          dsoStore,
          connection,
          store.key.dsoParty,
          implicit tc =>
            dsoStore.listDsoRulesTransferFollowers().flatMap { dsoRulesFollowers =>
              // don't try to schedule AmuletRules' followers if AmuletRules might move
              // (i.e. be one of dsoRulesFollowers)
              if (dsoRulesFollowers.nonEmpty) Future successful dsoRulesFollowers
              else dsoStore.listAmuletRulesTransferFollowers()
            },
        )
      )

      registerTrigger(
        new TransferFollowTrigger(
          triggerContext,
          svStore,
          connection,
          store.key.svParty,
          implicit tc =>
            dsoStore
              .lookupDsoRules()
              .flatMap(
                _.map(svStore.listDsoRulesTransferFollowers(_))
                  .getOrElse(Future successful Seq.empty)
              ),
        )
      )
    }
    registerTrigger(new AssignTrigger(triggerContext, dsoStore, connection, store.key.dsoParty))
    registerTrigger(
      new AnsSubscriptionInitialPaymentTrigger(triggerContext, dsoStore, connection)
    )
    registerTrigger(
      new SvPackageVettingTrigger(
        participantAdminConnection,
        dsoStore,
        config.prevetDuration,
        triggerContext,
      )
    )

    // SV status report triggers
    registerTrigger(
      new SubmitSvStatusReportTrigger(
        config,
        triggerContext,
        dsoStore,
        connection,
        cometBft,
        localSynchronizerNode.map(_.mediatorAdminConnection),
        participantAdminConnection,
      )
    )
    registerTrigger(
      new ReportSvStatusMetricsExportTrigger(
        triggerContext,
        dsoStore,
        cometBft,
      )
    )
    registerTrigger(
      new ReportValidatorLicenseMetricsExportTrigger(
        triggerContext,
        dsoStore,
      )
    )

    config.scan.foreach { scan =>
      registerTrigger(
        new PublishScanConfigTrigger(
          triggerContext,
          dsoStore,
          connection,
          scan,
          upgradesConfig,
        )
      )
    }

  }

  private val localSequencerClientContext: Option[LocalSequencerClientContext] =
    localSynchronizerNode.map(cfg =>
      LocalSequencerClientContext(
        cfg.sequencerAdminConnection,
        cfg.mediatorAdminConnection,
        Some(
          LocalSequencerClientConfig(
            cfg.sequencerInternalConfig,
            config.domains.global.alias,
          )
        ),
        cfg.sequencerPruningConfig.map(pruningConfig =>
          SequencerPruningConfig(
            pruningConfig.pruningInterval,
            pruningConfig.retentionPeriod,
          )
        ),
      )
    )

  localSequencerClientContext.flatMap(_.internalClientConfig).foreach { internalClientConfig =>
    registerTrigger(
      new LocalSequencerConnectionsTrigger(
        triggerContext,
        participantAdminConnection,
        internalClientConfig.decentralizedSynchronizerAlias,
        dsoStore,
        internalClientConfig.sequencerInternalConfig,
        config.domainMigrationId,
      )
    )
  }

  localSequencerClientContext.foreach { sequencerContext =>
    sequencerContext.pruningConfig.foreach { pruningConfig =>
      val contextWithSpecificPolling = triggerContext.copy(
        config = triggerContext.config.copy(
          pollingInterval = pruningConfig.pruningInterval
        )
      )
      registerTrigger(
        new SequencerPruningTrigger(
          contextWithSpecificPolling,
          dsoStore,
          sequencerContext.sequencerAdminConnection,
          sequencerContext.mediatorAdminConnection,
          clock,
          pruningConfig.retentionPeriod,
          participantAdminConnection,
          config.domainMigrationId,
        )
      )
    }
  }
}

object SvDsoAutomationService extends AutomationServiceCompanion {
  case class LocalSequencerClientContext(
      sequencerAdminConnection: SequencerAdminConnection,
      mediatorAdminConnection: MediatorAdminConnection,
      internalClientConfig: Option[LocalSequencerClientConfig],
      pruningConfig: Option[SequencerPruningConfig] = None,
  )

  case class LocalSequencerClientConfig(
      sequencerInternalConfig: ClientConfig,
      decentralizedSynchronizerAlias: DomainAlias,
  )

  private[automation] def bootstrapPackageIdResolver(template: QualifiedName): Option[String] =
    template.moduleName match {
      // DsoBootstrap is how we create AmuletRules in the first place so we cannot infer the package id for that from AmuletRules.
      case "Splice.DsoBootstrap" =>
        Some(DarResources.dsoGovernance.bootstrap.packageId)
      // ImportCrates are created before AmuletRules. Given that this is only a hack until we have upgrading
      // we can hardcode this.
      case "Splice.AmuletImport" =>
        Some(DarResources.amulet.bootstrap.packageId)
      case _ => None
    }

  // defined because some triggers are registered later by
  // registerPostOnboardingTriggers
  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] =
    SpliceAppAutomationService.expectedTriggerClasses ++ Seq(
      aTrigger[SummarizingMiningRoundTrigger],
      aTrigger[SvOnboardingRequestTrigger],
      aTrigger[ReceiveSvRewardCouponTrigger],
      aTrigger[ArchiveClosedMiningRoundsTrigger],
      aTrigger[ElectionRequestTrigger],
      aTrigger[RestartDsoDelegateBasedAutomationTrigger],
      aTrigger[AmuletConfigReassignmentTrigger],
      aTrigger[AssignTrigger],
      aTrigger[TransferFollowTrigger],
      aTrigger[AnsSubscriptionInitialPaymentTrigger],
      aTrigger[SvPackageVettingTrigger],
      aTrigger[SvOffboardingPartyToParticipantProposalTrigger],
      aTrigger[SvOffboardingMediatorTrigger],
      aTrigger[SvOnboardingUnlimitedTrafficTrigger],
      aTrigger[SvOffboardingSequencerTrigger],
      aTrigger[ReconcileSequencerLimitWithMemberTrafficTrigger],
      aTrigger[SvNamespaceMembershipTrigger],
      aTrigger[SvOnboardingPromoteParticipantToSubmitterTrigger],
      aTrigger[SvOnboardingPartyToParticipantProposalTrigger],
      aTrigger[SvOnboardingSequencerTrigger],
      aTrigger[SvOnboardingMediatorProposalTrigger],
      aTrigger[DecentralizedSynchronizerMigrationTrigger],
      aTrigger[PublishLocalCometBftNodeConfigTrigger],
      aTrigger[PublishScanConfigTrigger],
      aTrigger[ReconcileCometBftNetworkConfigWithDsoRulesTrigger],
      aTrigger[LocalSequencerConnectionsTrigger],
      aTrigger[SequencerPruningTrigger],
      aTrigger[SubmitSvStatusReportTrigger],
      aTrigger[ReportSvStatusMetricsExportTrigger],
      aTrigger[ReportValidatorLicenseMetricsExportTrigger],
      aTrigger[ReconcileDynamicDomainParametersTrigger],
    )
}
