// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.admin.http

import com.daml.network.environment.{BuildInfo, SpliceStatus}
import com.daml.network.http.v0.definitions
import com.daml.network.http.v0.external.common_admin as v0
import com.digitalasset.canton.health.admin.data
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import java.time.{Instant, OffsetDateTime, ZoneOffset}
import scala.concurrent.{ExecutionContext, Future}

class HttpAdminHandler(
    status: => Future[data.NodeStatus[SpliceStatus]],
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.CommonAdminHandler[TraceContext]
    with Spanning
    with NamedLogging {
  protected val workflowId = this.getClass.getSimpleName

  def getHealthStatus(
      respond: v0.CommonAdminResource.GetHealthStatusResponse.type
  )()(extracted: TraceContext): Future[v0.CommonAdminResource.GetHealthStatusResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getHealthStatus") { _ => _ =>
      status
        .map(s => respond.OK(SpliceStatus.toHttpNodeStatus(s)))
    }
  }

  override def getVersion(
      respond: v0.CommonAdminResource.GetVersionResponse.type
  )()(extracted: TraceContext): Future[v0.CommonAdminResource.GetVersionResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getVersion") { _ => _ =>
      Future(
        respond.OK(
          definitions.Version(
            version = BuildInfo.compiledVersion,
            commitTs = OffsetDateTime.ofInstant(
              Instant.ofEpochSecond(BuildInfo.commitUnixTimestamp.toLong),
              ZoneOffset.UTC,
            ),
          )
        )
      )
    }
  }

  override def isLive(
      respond: v0.CommonAdminResource.IsLiveResponse.type
  )()(extracted: TraceContext): Future[v0.CommonAdminResource.IsLiveResponse] = {
    {
      implicit val tc = extracted
      withSpan(s"$workflowId.isLive") { _ => _ =>
        Future(respond.OK)
      }
    }
  }

  override def isReady(
      respond: v0.CommonAdminResource.IsReadyResponse.type
  )()(extracted: TraceContext): Future[v0.CommonAdminResource.IsReadyResponse] = {
    {
      implicit val tc = extracted
      withSpan(s"$workflowId.isReady") { _ => _ =>
        status.map { s =>
          if (s.isActive.exists(identity)) respond.OK
          else respond.ServiceUnavailable
        }
      }
    }
  }
}
