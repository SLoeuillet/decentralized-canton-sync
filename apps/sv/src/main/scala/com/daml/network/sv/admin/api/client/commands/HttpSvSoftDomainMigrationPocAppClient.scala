// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.http.HttpClient
import com.daml.network.http.v0.{sv_soft_domain_migration_poc as http}
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

object HttpSvSoftDomainMigrationPocAppClient {
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.SvSoftDomainMigrationPocClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.SvSoftDomainMigrationPocClient.httpClient(
        HttpClientBuilder().buildClient(),
        host,
      )
  }

  case class SignSynchronizerBootstrappingState(domainIdPrefix: String)
      extends BaseCommand[http.SignSynchronizerBootstrappingStateResponse, Unit] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.SignSynchronizerBootstrappingStateResponse] =
      client.signSynchronizerBootstrappingState(domainIdPrefix, headers = headers)
    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.SignSynchronizerBootstrappingStateResponse.OK => Right(())
    }
  }

  case class InitializeSynchronizer(domainIdPrefix: String)
      extends BaseCommand[http.InitializeSynchronizerResponse, Unit] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.InitializeSynchronizerResponse] =
      client.initializeSynchronizer(domainIdPrefix, headers = headers)
    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.InitializeSynchronizerResponse.OK => Right(())
    }
  }

  case class ReconcileSynchronizerDamlState(domainIdPrefix: String)
      extends BaseCommand[http.ReconcileSynchronizerDamlStateResponse, Unit] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ReconcileSynchronizerDamlStateResponse] =
      client.reconcileSynchronizerDamlState(domainIdPrefix, headers = headers)
    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.ReconcileSynchronizerDamlStateResponse.OK => Right(())
    }
  }

  case class SignDsoPartyToParticipant(domainIdPrefix: String)
      extends BaseCommand[http.SignDsoPartyToParticipantResponse, Unit] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.SignDsoPartyToParticipantResponse] =
      client.signDsoPartyToParticipant(domainIdPrefix, headers = headers)
    override def handleOk()(implicit decoder: TemplateJsonDecoder) = {
      case http.SignDsoPartyToParticipantResponse.OK => Right(())
    }
  }
}
