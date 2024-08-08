// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.migration

import cats.syntax.traverse.*
import com.daml.network.environment.{ParticipantAdminConnection, SequencerAdminConnection}
import com.daml.network.migration.{
  AcsExporter,
  DarExporter,
  DomainParametersStateTopologyConnection,
}
import com.daml.network.sv.store.SvDsoStore
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class DomainDataSnapshotGenerator(
    participantAdminConnection: ParticipantAdminConnection,
    // TODO(#11099) Read everything from the participant connection once the genesis state API is available there.
    sequencerAdminConnection: Option[SequencerAdminConnection],
    dsoStore: SvDsoStore,
    acsExporter: AcsExporter,
    override val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {

  private val darExporter = new DarExporter(participantAdminConnection)

  private val domainStateTopology = new DomainParametersStateTopologyConnection(
    participantAdminConnection
  )

  def getDomainDataSnapshot(
      timestamp: Instant,
      partyId: Option[PartyId],
      force: Boolean,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DomainDataSnapshot] = for {
    decentralizedSynchronizer <- dsoStore.getDsoRules().map(_.domain)
    cantonTimestamp = CantonTimestamp.tryFromInstant(timestamp)
    topologySnapshot <- sequencerAdminConnection.traverse(_.getGenesisState(cantonTimestamp))
    acsSnapshot <- acsExporter
      .exportAcsAtTimestamp(
        decentralizedSynchronizer,
        timestamp,
        force,
        partyId.fold(Seq(dsoStore.key.dsoParty, dsoStore.key.svParty))(Seq(_))*
      )
    dars <- darExporter.exportAllDars()
  } yield DomainDataSnapshot(
    topologySnapshot,
    acsSnapshot,
    acsTimestamp = timestamp,
    dars,
  )

  def getDomainMigrationSnapshot(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DomainDataSnapshot] = for {
    decentralizedSynchronizer <- dsoStore.getDsoRules().map(_.domain)
    domainParamsStateTopology <- domainStateTopology
      .firstAuthorizedStateForTheLatestDomainParametersState(
        decentralizedSynchronizer
      )
      .getOrElse {
        throw Status.FAILED_PRECONDITION
          .withDescription("No domain state topology found")
          .asRuntimeException()
      }
    timestamp = CantonTimestamp.tryFromInstant(domainParamsStateTopology.base.validFrom)
    genesisState <- sequencerAdminConnection.traverse(
      _.getGenesisState(timestamp)
    )
    (acsSnapshot, acsTimestamp) <- acsExporter
      .safeExportParticipantPartiesAcsFromPausedDomain(decentralizedSynchronizer)
      .leftMap(failure =>
        Status.FAILED_PRECONDITION.withDescription(failure.toString).asRuntimeException()
      )
      .rethrowT
    dars <- darExporter.exportAllDars()
  } yield {
    val result = DomainDataSnapshot(
      genesisState,
      acsSnapshot,
      acsTimestamp,
      dars,
    )
    logger.info(show"Finished generating $result")
    result
  }
}
