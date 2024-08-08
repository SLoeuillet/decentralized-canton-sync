// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.automation

import org.apache.pekko.stream.Materializer
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{
  SpliceLedgerClient,
  SpliceLedgerConnection,
  PackageIdResolver,
  RetryProvider,
}
import com.daml.network.store.{
  AppStore,
  AppStoreWithIngestion,
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.time.{Clock, WallClock}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** A class that wires up a single store with an ingestion service, and provides a suitable
  * context for registering triggers against that store.
  */
abstract class SpliceAppAutomationService[Store <: AppStore](
    automationConfig: AutomationConfig,
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
    override val store: Store,
    packageIdResolver: PackageIdResolver,
    ledgerClient: SpliceLedgerClient,
    retryProvider: RetryProvider,
    ingestFromParticipantBegin: Boolean,
    ingestUpdateHistoryFromParticipantBegin: Boolean,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(
      automationConfig,
      clock,
      domainTimeSync,
      domainUnpausedSync,
      retryProvider,
    )
    with AppStoreWithIngestion[Store] {

  override val connection: SpliceLedgerConnection =
    ledgerClient.connection(
      this.getClass.getSimpleName,
      loggerFactory,
      packageIdResolver,
      completionOffsetCallback,
    )

  private def completionOffsetCallback(offset: String): Future[Unit] =
    store.multiDomainAcsStore.signalWhenIngestedOrShutdown(offset)(TraceContext.empty)

  registerService(
    new UpdateIngestionService(
      store.getClass.getSimpleName,
      store.multiDomainAcsStore.ingestionSink,
      connection,
      automationConfig,
      backoffClock = triggerContext.pollingClock,
      triggerContext.retryProvider,
      triggerContext.loggerFactory,
      ingestFromParticipantBegin,
    )
  )

  registerService(
    new UpdateIngestionService(
      store.updateHistory.getClass.getSimpleName,
      store.updateHistory.ingestionSink,
      connection,
      automationConfig,
      backoffClock = triggerContext.pollingClock,
      triggerContext.retryProvider,
      triggerContext.loggerFactory,
      ingestUpdateHistoryFromParticipantBegin,
    )
  )

  registerTrigger(
    new DomainIngestionService(
      store.domains.ingestionSink,
      connection,
      // We want to always poll periodically and quickly even in simtime mode so we overwrite
      // the polling interval and the clock.
      triggerContext.copy(
        config = triggerContext.config.copy(
          pollingInterval = NonNegativeFiniteDuration.ofSeconds(1)
        ),
        clock = new WallClock(
          triggerContext.timeouts,
          triggerContext.loggerFactory,
        ),
      ),
    )
  )
}

object SpliceAppAutomationService {
  import AutomationServiceCompanion.*
  def expectedTriggerClasses: Seq[TriggerClass] =
    Seq(aTrigger[DomainIngestionService])
}
