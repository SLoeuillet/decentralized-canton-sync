// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.scan.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse, StatusCodes, Uri}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.codegen.java.splice.amulet.FeaturedAppRight
import com.daml.network.codegen.java.splice.amuletrules.{AmuletRules, AppTransferContext}
import com.daml.network.codegen.java.splice.round.{
  ClosedMiningRound,
  IssuingMiningRound,
  OpenMiningRound,
}
import com.daml.network.codegen.java.splice.ans as ansCodegen
import com.daml.network.codegen.java.splice.ans.AnsRules
import com.daml.network.http.HttpClient
import com.daml.network.http.v0.{definitions, scan as http}
import com.daml.network.http.v0.external.scan as externalHttp
import com.daml.network.scan.store.db.ScanAggregator
import com.daml.network.store.MultiDomainAcsStore
import com.daml.network.util.{Codec, Contract, ContractWithState, TemplateJsonDecoder}
import com.digitalasset.canton.topology.{DomainId, Member, ParticipantId, PartyId, SequencerId}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString

import java.util.Base64
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*
import scala.util.Try

object HttpScanAppClient {

  abstract class InternalBaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.ScanClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.ScanClient.httpClient(HttpClientBuilder().buildClient(Set(StatusCodes.NotFound)), host)
  }

  abstract class ExternalBaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = externalHttp.ScanClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      externalHttp.ScanClient.httpClient(HttpClientBuilder().buildClient(), host)
  }

  case class GetDsoPartyId(headers: List[HttpHeader])
      extends InternalBaseCommand[http.GetDsoPartyIdResponse, PartyId] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetDsoPartyIdResponse] =
      client.getDsoPartyId(headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetDsoPartyIdResponse.OK(response) =>
      Codec.decode(Codec.Party)(response.dsoPartyId)
    }
  }

  case class GetDsoInfo(headers: List[HttpHeader])
      extends InternalBaseCommand[http.GetDsoInfoResponse, definitions.GetDsoInfoResponse] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetDsoInfoResponse] =
      client.getDsoInfo(headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetDsoInfoResponse.OK(response) =>
      Right(response)
    }
  }

  /** Very similar to the AppTransferContext we use in Daml, except
    * (1) this class has contract instances, not just (interface) contract-ids of the respective Daml contracts.
    * (2) this class has no featuredAppRight contract.
    */
  case class TransferContextWithInstances(
      amuletRules: ContractWithState[AmuletRules.ContractId, AmuletRules],
      latestOpenMiningRound: ContractWithState[
        OpenMiningRound.ContractId,
        OpenMiningRound,
      ],
      openMiningRounds: Seq[
        ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]
      ],
  ) {
    def toUnfeaturedAppTransferContext() = {
      val openMiningRound = latestOpenMiningRound
      new AppTransferContext(
        amuletRules.contractId,
        openMiningRound.contractId,
        None.toJava,
      )
    }
  }

  /** Rounds are sorted in ascending order according to their round number. */
  case class GetSortedOpenAndIssuingMiningRounds(
      cachedOpenRounds: Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
      cachedIssuingRounds: Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
  ) extends InternalBaseCommand[
        http.GetOpenAndIssuingMiningRoundsResponse,
        (
            Seq[ContractWithState[OpenMiningRound.ContractId, OpenMiningRound]],
            Seq[ContractWithState[IssuingMiningRound.ContractId, IssuingMiningRound]],
            BigInt,
        ),
      ] {

    private val cachedOpenRoundsMap =
      cachedOpenRounds.map(r => (r.contractId.contractId, r)).toMap
    private val cachedIssuingRoundsMap =
      cachedIssuingRounds.map(r => (r.contractId.contractId, r)).toMap

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetOpenAndIssuingMiningRoundsResponse] =
      client.getOpenAndIssuingMiningRounds(
        definitions.GetOpenAndIssuingMiningRoundsRequest(
          cachedOpenRounds.map(_.contractId.contractId).toVector,
          cachedIssuingRounds.map(_.contractId.contractId).toVector,
        ),
        headers,
      )

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetOpenAndIssuingMiningRoundsResponse.OK(response) =>
        for {
          issuingMiningRounds <- response.issuingMiningRounds.toSeq.traverse {
            case (contractId, maybeIssuingRound) =>
              ContractWithState.handleMaybeCached(IssuingMiningRound.COMPANION)(
                cachedIssuingRoundsMap.get(contractId),
                maybeIssuingRound,
              )
          }
          openMiningRounds <- response.openMiningRounds.toSeq.traverse {
            case (contractId, maybeOpenRound) =>
              ContractWithState.handleMaybeCached(OpenMiningRound.COMPANION)(
                cachedOpenRoundsMap.get(contractId),
                maybeOpenRound,
              )
          }
        } yield (
          openMiningRounds.sortBy(_.payload.round.number),
          issuingMiningRounds.sortBy(_.payload.round.number),
          response.timeToLiveInMicroseconds,
        )
    }
  }

  case class GetAmuletRules(
      cachedAmuletRules: Option[ContractWithState[AmuletRules.ContractId, AmuletRules]]
  ) extends InternalBaseCommand[
        http.GetAmuletRulesResponse,
        ContractWithState[AmuletRules.ContractId, AmuletRules],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetAmuletRulesResponse] = {
      import MultiDomainAcsStore.ContractState.*
      client.getAmuletRules(
        definitions.GetAmuletRulesRequest(
          cachedAmuletRules.map(_.contractId.contractId),
          cachedAmuletRules.flatMap(_.state match {
            case Assigned(domain) => Some(domain.toProtoPrimitive)
            case InFlight => None
          }),
        ),
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetAmuletRulesResponse.OK(response) =>
        for {
          amuletRules <- ContractWithState.handleMaybeCached(AmuletRules.COMPANION)(
            cachedAmuletRules,
            response.amuletRulesUpdate,
          )
        } yield amuletRules
    }
  }

  case class GetAnsRules(
      cachedAnsRules: Option[ContractWithState[AnsRules.ContractId, AnsRules]]
  ) extends InternalBaseCommand[
        http.GetAnsRulesResponse,
        ContractWithState[AnsRules.ContractId, AnsRules],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetAnsRulesResponse] = {
      import MultiDomainAcsStore.ContractState.*
      client.getAnsRules(
        definitions.GetAnsRulesRequest(
          cachedAnsRules.map(_.contractId.contractId),
          cachedAnsRules.flatMap(_.state match {
            case Assigned(domain) => Some(domain.toProtoPrimitive)
            case InFlight => None
          }),
        ),
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetAnsRulesResponse.OK(response) =>
        for {
          ansRules <- ContractWithState.handleMaybeCached(ansCodegen.AnsRules.COMPANION)(
            cachedAnsRules,
            response.ansRulesUpdate,
          )
        } yield ansRules
    }
  }

  case object GetClosedRounds
      extends InternalBaseCommand[
        http.GetClosedRoundsResponse,
        Seq[Contract[ClosedMiningRound.ContractId, ClosedMiningRound]],
      ] {

    def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetClosedRoundsResponse] =
      client.getClosedRounds(headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetClosedRoundsResponse.OK(response) =>
      response.rounds
        .traverse(round => Contract.fromHttp(ClosedMiningRound.COMPANION)(round))
        .leftMap(_.toString)
    }
  }

  case object ListFeaturedAppRight
      extends InternalBaseCommand[
        http.ListFeaturedAppRightsResponse,
        Seq[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListFeaturedAppRightsResponse] =
      client.listFeaturedAppRights(headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListFeaturedAppRightsResponse.OK(response) =>
      response.featuredApps
        .traverse(co => Contract.fromHttp(FeaturedAppRight.COMPANION)(co))
        .leftMap(_.toString)
    }
  }

  case class LookupFeaturedAppRight(providerPartyId: PartyId)
      extends InternalBaseCommand[
        http.LookupFeaturedAppRightResponse,
        Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.LookupFeaturedAppRightResponse] =
      client.lookupFeaturedAppRight(providerPartyId.toProtoPrimitive, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.LookupFeaturedAppRightResponse.OK(response) =>
      response.featuredAppRight
        .traverse(co => Contract.fromHttp(FeaturedAppRight.COMPANION)(co))
        .leftMap(_.toString)
    }
  }

  case class ListAnsEntries(
      namePrefix: Option[String],
      pageSize: Int,
  ) extends InternalBaseCommand[http.ListAnsEntriesResponse, Seq[definitions.AnsEntry]] {

    def submitRequest(client: Client, headers: List[HttpHeader]) =
      client.listAnsEntries(namePrefix, pageSize, headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListAnsEntriesResponse.OK(response) =>
      Right(response.entries)
    }
  }

  case class LookupAnsEntryByParty(
      party: PartyId
  ) extends InternalBaseCommand[http.LookupAnsEntryByPartyResponse, Option[definitions.AnsEntry]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.lookupAnsEntryByParty(party.toProtoPrimitive, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.LookupAnsEntryByPartyResponse.OK(response) =>
        Right(Some(response.entry))
      case http.LookupAnsEntryByPartyResponse.NotFound(_) =>
        Right(None)
    }
  }

  case class LookupAnsEntryByName(
      name: String
  ) extends InternalBaseCommand[http.LookupAnsEntryByNameResponse, Option[definitions.AnsEntry]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ) = client.lookupAnsEntryByName(name, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.LookupAnsEntryByNameResponse.OK(response) =>
        Right(Some(response.entry))
      case http.LookupAnsEntryByNameResponse.NotFound(_) =>
        Right(None)
    }
  }

  case class GetTotalAmuletBalance(asOfEndOfRound: Long)
      extends InternalBaseCommand[http.GetTotalAmuletBalanceResponse, BigDecimal] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetTotalAmuletBalanceResponse] =
      client.getTotalAmuletBalance(asOfEndOfRound, headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetTotalAmuletBalanceResponse.OK(response) =>
        Codec.decode(Codec.BigDecimal)(response.totalBalance)
      case http.GetTotalAmuletBalanceResponse.NotFound(err) =>
        Left(err.error)
    }
  }

  final case class RateStep(
      amount: BigDecimal,
      rate: BigDecimal,
  )
  final case class SteppedRate(
      initial: BigDecimal,
      steps: Seq[RateStep],
  )
  final case class AmuletConfig(
      amuletCreateFee: BigDecimal,
      holdingFee: BigDecimal,
      lockHolderFee: BigDecimal,
      transferFee: SteppedRate,
  )
  case class GetAmuletConfigForRound(round: Long)
      extends InternalBaseCommand[http.GetAmuletConfigForRoundResponse, AmuletConfig] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetAmuletConfigForRoundResponse] =
      client.getAmuletConfigForRound(round, headers)

    private def decodeStep(step: definitions.RateStep): Either[String, RateStep] =
      for {
        amount <- Codec.decode(Codec.BigDecimal)(step.amount)
        rate <- Codec.decode(Codec.BigDecimal)(step.rate)
      } yield RateStep(amount, rate)

    private def decodeTransferFeeSteps(
        tf: Seq[definitions.RateStep]
    ): Either[String, Seq[RateStep]] =
      tf.map(decodeStep(_)).sequence

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.GetAmuletConfigForRoundResponse.OK(response) =>
        for {
          amuletCreate <- Codec.decode(Codec.BigDecimal)(response.amuletCreateFee)
          holding <- Codec.decode(Codec.BigDecimal)(response.holdingFee)
          lockHolder <- Codec.decode(Codec.BigDecimal)(response.lockHolderFee)
          initial <- Codec.decode(Codec.BigDecimal)(response.transferFee.initial)
          steps <- decodeTransferFeeSteps(response.transferFee.steps.toSeq)
        } yield {
          AmuletConfig(
            amuletCreateFee = amuletCreate,
            holdingFee = holding,
            lockHolderFee = lockHolder,
            transferFee = SteppedRate(
              initial = initial,
              steps = steps,
            ),
          )
        }
      case http.GetAmuletConfigForRoundResponse.NotFound(err) =>
        Left(err.error)
    }
  }

  case class GetRoundOfLatestData()
      extends InternalBaseCommand[http.GetRoundOfLatestDataResponse, (Long, Instant)] {

    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetRoundOfLatestDataResponse] =
      client.getRoundOfLatestData(headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetRoundOfLatestDataResponse.OK(response) =>
        Right((response.round, response.effectiveAt.toInstant))
      case http.GetRoundOfLatestDataResponse.NotFound(err) =>
        Left(err.error)
    }
  }

  case class GetRewardsCollected(round: Option[Long])
      extends InternalBaseCommand[http.GetRewardsCollectedResponse, BigDecimal] {

    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetRewardsCollectedResponse] =
      client.getRewardsCollected(round, headers)

    override protected def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetRewardsCollectedResponse.OK(response) =>
        for {
          amount <- Codec.decode(Codec.BigDecimal)(response.amount)
        } yield amount
      case http.GetRewardsCollectedResponse.NotFound(err) =>
        Left(err.error)
    }
  }

  private def decodePartiesAndRewards(
      partiesAndRewards: Vector[definitions.PartyAndRewards]
  ): Either[String, Seq[(PartyId, BigDecimal)]] =
    partiesAndRewards.traverse(par =>
      for {
        p <- Codec.decode(Codec.Party)(par.provider)
        r <- Codec.decode(Codec.BigDecimal)(par.rewards)
      } yield (p, r)
    )

  case class getTopProvidersByAppRewards(asOfEndOfRound: Long, limit: Int)
      extends InternalBaseCommand[http.GetTopProvidersByAppRewardsResponse, Seq[
        (PartyId, BigDecimal)
      ]] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetTopProvidersByAppRewardsResponse] =
      client.getTopProvidersByAppRewards(asOfEndOfRound, limit, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.GetTopProvidersByAppRewardsResponse.OK(response) =>
        decodePartiesAndRewards(response.providersAndRewards)
      case http.GetTopProvidersByAppRewardsResponse.NotFound(err) =>
        Left(err.error)
    }
  }

  case class getTopValidatorsByValidatorRewards(asOfEndOfRound: Long, limit: Int)
      extends InternalBaseCommand[http.GetTopValidatorsByValidatorRewardsResponse, Seq[
        (PartyId, BigDecimal)
      ]] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetTopValidatorsByValidatorRewardsResponse] =
      client.getTopValidatorsByValidatorRewards(asOfEndOfRound, limit, headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.GetTopValidatorsByValidatorRewardsResponse.OK(response) =>
        decodePartiesAndRewards(response.validatorsAndRewards)
      case http.GetTopValidatorsByValidatorRewardsResponse.NotFound(err) =>
        Left(err.error)
    }
  }

  final case class ValidatorPurchasedTraffic(
      validator: PartyId,
      numPurchases: Long,
      totalTrafficPurchased: Long,
      totalCcSpent: BigDecimal,
      lastPurchasedInRound: Long,
  )

  case class GetTopValidatorsByPurchasedTraffic(asOfEndOfRound: Long, limit: Int)
      extends InternalBaseCommand[http.GetTopValidatorsByPurchasedTrafficResponse, Seq[
        ValidatorPurchasedTraffic
      ]] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetTopValidatorsByPurchasedTrafficResponse] =
      client.getTopValidatorsByPurchasedTraffic(asOfEndOfRound, limit, headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetTopValidatorsByPurchasedTrafficResponse.OK(response) =>
        response.validatorsByPurchasedTraffic.traverse(decodeValidatorPurchasedTraffic)
      case http.GetTopValidatorsByPurchasedTrafficResponse.NotFound(err) =>
        Left(err.error)
    }

    private def decodeValidatorPurchasedTraffic(traffic: definitions.ValidatorPurchasedTraffic) = {
      for {
        vp <- Codec.decode(Codec.Party)(traffic.validator)
        n = traffic.numPurchases
        tot = traffic.totalTrafficPurchased
        cc <- Codec.decode(Codec.BigDecimal)(traffic.totalCcSpent)
        lpr = traffic.lastPurchasedInRound
      } yield ValidatorPurchasedTraffic(vp, n, tot, cc, lpr)
    }
  }

  case class GetMemberTrafficStatus(domainId: DomainId, memberId: Member)
      extends ExternalBaseCommand[
        externalHttp.GetMemberTrafficStatusResponse,
        definitions.MemberTrafficStatus,
      ] {

    override def submitRequest(
        client: externalHttp.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], externalHttp.GetMemberTrafficStatusResponse] =
      client.getMemberTrafficStatus(domainId.toProtoPrimitive, memberId.toProtoPrimitive, headers)

    override protected def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case externalHttp.GetMemberTrafficStatusResponse.OK(response) => Right(response.trafficStatus)
    }
  }

  case class GetPartyToParticipant(domainId: DomainId, partyId: PartyId)
      extends ExternalBaseCommand[
        externalHttp.GetPartyToParticipantResponse,
        ParticipantId,
      ] {

    override def submitRequest(
        client: externalHttp.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], externalHttp.GetPartyToParticipantResponse] =
      client.getPartyToParticipant(domainId.toProtoPrimitive, partyId.toProtoPrimitive, headers)

    override protected def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case externalHttp.GetPartyToParticipantResponse.OK(response) =>
        for {
          participantId <- Codec.decode(Codec.Participant)(response.participantId)
        } yield participantId
    }
  }

  case class ListDsoSequencers()
      extends InternalBaseCommand[
        http.ListDsoSequencersResponse,
        Seq[DomainSequencers],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListDsoSequencersResponse] =
      client.listDsoSequencers(headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListDsoSequencersResponse.OK(response) =>
        response.domainSequencers.traverse { domain =>
          // TODO (#9309): malicious scans can make these decoding fail
          Codec.decode(Codec.DomainId)(domain.domainId).flatMap { domainId =>
            domain.sequencers
              .traverse { s =>
                Codec.decode(Codec.Sequencer)(s.id).map { sequencerId =>
                  DsoSequencer(
                    s.migrationId,
                    sequencerId,
                    s.url,
                    s.svName,
                    s.availableAfter.toInstant,
                  )
                }
              }
              .map { sequencers =>
                DomainSequencers(domainId, sequencers)
              }
          }
        }
    }
  }

  final case class DomainSequencers(domainId: DomainId, sequencers: Seq[DsoSequencer])

  final case class DsoSequencer(
      migrationId: Long,
      id: SequencerId,
      url: String,
      svName: String,
      availableAfter: Instant,
  )

  case class ListDsoScans()
      extends InternalBaseCommand[
        http.ListDsoScansResponse,
        Seq[DomainScans],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListDsoScansResponse] =
      client.listDsoScans(headers)

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListDsoScansResponse.OK(response) =>
        response.scans.traverse { domain =>
          // TODO (#9309): malicious scans can make this decoding fail
          Codec.decode(Codec.DomainId)(domain.domainId).map { domainId =>
            // all SVs validate the Uri, so this should only fail to parse for malicious SVs.
            val (malformed, scanList) =
              domain.scans.partitionMap(scan =>
                Try(Uri(scan.publicUrl)).toEither
                  .bimap(scan.publicUrl -> _, url => DsoScan(url, scan.svName))
              )
            DomainScans(domainId, scanList, malformed.toMap)
          }
        }
    }
  }
  final case class DomainScans(
      domainId: DomainId,
      scans: Seq[DsoScan],
      malformed: Map[String, Throwable],
  )

  final case class DsoScan(publicUrl: Uri, svName: String)

  case class ListTransactions(
      pageEndEventId: Option[String],
      sortOrder: definitions.TransactionHistoryRequest.SortOrder,
      pageSize: Int,
  ) extends InternalBaseCommand[http.ListTransactionHistoryResponse, Seq[
        definitions.TransactionHistoryResponseItem
      ]] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListTransactionHistoryResponse] = {
      client.listTransactionHistory(
        definitions
          .TransactionHistoryRequest(pageEndEventId, Some(sortOrder), pageSize.toLong),
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListTransactionHistoryResponse.OK(response) =>
        Right(response.transactions)
    }
  }

  case class GetAcsSnapshot(
      party: PartyId
  ) extends InternalBaseCommand[http.GetAcsSnapshotResponse, ByteString] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetAcsSnapshotResponse] = {
      client.getAcsSnapshot(party.toProtoPrimitive, headers)
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetAcsSnapshotResponse.OK(response) =>
        Right(ByteString.copyFrom(Base64.getDecoder.decode(response.acsSnapshot)))
    }
  }
  object GetAggregatedRounds
      extends InternalBaseCommand[http.GetAggregatedRoundsResponse, Option[
        ScanAggregator.RoundRange
      ]] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetAggregatedRoundsResponse] = {
      client.getAggregatedRounds(headers)
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetAggregatedRoundsResponse.OK(response) =>
        Right(Some(ScanAggregator.RoundRange(response.start, response.end)))
      case http.GetAggregatedRoundsResponse.NotFound(_) =>
        Right(None)
    }
  }
  case class ListRoundTotals(start: Long, end: Long)
      extends InternalBaseCommand[
        http.ListRoundTotalsResponse,
        Seq[definitions.RoundTotals],
      ] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListRoundTotalsResponse] = {
      client.listRoundTotals(definitions.ListRoundTotalsRequest(start, end), headers)
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListRoundTotalsResponse.OK(response) =>
        Right(response.entries)
    }
  }

  case class ListRoundPartyTotals(start: Long, end: Long)
      extends InternalBaseCommand[
        http.ListRoundPartyTotalsResponse,
        Seq[definitions.RoundPartyTotals],
      ] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListRoundPartyTotalsResponse] = {
      client.listRoundPartyTotals(definitions.ListRoundPartyTotalsRequest(start, end), headers)
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ListRoundPartyTotalsResponse.OK(response) =>
        Right(response.entries)
    }
  }

  case class GetMigrationSchedule()
      extends InternalBaseCommand[
        http.GetMigrationScheduleResponse,
        Option[definitions.MigrationSchedule],
      ] {

    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetMigrationScheduleResponse] = {
      client.getMigrationSchedule(headers)
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetMigrationScheduleResponse.OK(response) =>
        Right(Some(response))
      case http.GetMigrationScheduleResponse.NotFound =>
        Right(None)
    }
  }

  case class GetUpdateHistory(count: Int, after: Option[(Long, String)], lossless: Boolean)
      extends InternalBaseCommand[http.GetUpdateHistoryResponse, Seq[
        definitions.UpdateHistoryItem
      ]] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetUpdateHistoryResponse] = {
      client.getUpdateHistory(
        definitions.UpdateHistoryRequest(
          after.map { case (migrationId, recordTime) =>
            definitions.UpdateHistoryRequestAfter(migrationId, recordTime)
          },
          count,
          Some(lossless),
        ),
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetUpdateHistoryResponse.OK(response) =>
        Right(response.transactions)
    }
  }

  case class GetUpdate(updateId: String)
      extends InternalBaseCommand[http.GetUpdateByIdResponse, definitions.UpdateHistoryItem] {
    override def submitRequest(
        client: http.ScanClient,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetUpdateByIdResponse] = {
      client.getUpdateById(
        updateId,
        headers,
      )
    }

    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.GetUpdateByIdResponse.OK(response) =>
        Right(response)
    }
  }
}
