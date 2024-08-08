// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.admin.http

import com.daml.network.admin.api.TraceContextDirectives.withTraceContext
import com.daml.network.environment.SpliceStatus
import com.daml.network.http.v0.external.common_admin.CommonAdminResource
import com.digitalasset.canton.config.AdminServerConfig
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.environment.{CantonNode, CantonNodeParameters}
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.lifecycle.{AsyncCloseable, Lifecycle}
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

trait AdminRoutes {
  def commonAdminRoute: Route
  def updateRoute(route: Route): Unit
}

trait HttpAdminService extends AdminRoutes with AutoCloseable
object HttpAdminService {
  def apply(
      nodeTypeName: String,
      adminApi: AdminServerConfig,
      parameterConfig: CantonNodeParameters,
      loggerFactory: NamedLoggerFactory,
      node: => Option[CantonNode],
  )(implicit
      ac: ActorSystem,
      ec: ExecutionContext,
      tracer: Tracer,
      elc: ErrorLoggingContext,
  ): HttpAdminService = new HttpAdminServiceImpl(
    nodeTypeName,
    adminApi.address,
    adminApi.port,
    parameterConfig,
    loggerFactory,
    node,
  )

  private class HttpAdminServiceImpl(
      nodeTypeName: String,
      address: String,
      port: Port,
      parameterConfig: CantonNodeParameters,
      loggerFactory: NamedLoggerFactory,
      node: => Option[CantonNode],
  )(implicit ac: ActorSystem, ec: ExecutionContext, tracer: Tracer, elc: ErrorLoggingContext)
      extends HttpAdminService {

    private def status(): Future[NodeStatus[SpliceStatus]] = node
      .map { n =>
        n.status
          .map(SpliceStatus.fromNodeStatus)
          .map(NodeStatus.Success(_))
      }
      .getOrElse(Future.successful(NodeStatus.NotInitialized(active = false, None)))
    private val logger = loggerFactory.getTracedLogger(this.getClass)
    private val adminHandler = new HttpAdminHandler(
      status(),
      loggerFactory,
    )
    private val routes: AtomicReference[List[Route]] = new AtomicReference(List())
    private val dynamicRoute: Route = ctx => {
      concat((commonAdminRoute +: routes.get())*)(ctx)
    }

    val commonAdminRoute: Route =
      withTraceContext { traceContext =>
        HttpErrorHandler(loggerFactory)(traceContext) {
          concat(
            pathPrefix("api" / nodeTypeName.toLowerCase)(
              CommonAdminResource.routes(adminHandler, _ => provide(traceContext))
            )
          )
        }
      }
    private val bindingF = Http()
      .newServerAt(
        address,
        port.unwrap,
      )
      .bind(
        dynamicRoute
      )

    override def close(): Unit = {
      Lifecycle.close(
        AsyncCloseable(
          "http binding admin service",
          bindingF.flatMap {
            _.terminate(hardDeadline =
              parameterConfig.processingTimeouts.shutdownShort.asFiniteApproximation
            )
          },
          parameterConfig.processingTimeouts.shutdownNetwork,
        )
      )(logger)
    }

    override def updateRoute(route: Route): Unit = {
      routes.set(List(route))
    }
  }
}
