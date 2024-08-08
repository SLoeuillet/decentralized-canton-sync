// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.cometbft

sealed trait CometBftHealth

case object HealthyCometBftNode extends CometBftHealth
final case class UnhealthyCometBftNode(httpStatus: Int) extends CometBftHealth
