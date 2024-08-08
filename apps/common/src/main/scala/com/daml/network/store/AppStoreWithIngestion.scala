// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.store

import com.daml.network.environment.SpliceLedgerConnection
import com.daml.network.store.AppStore

/** A trait for stores that have been wired up with an ingestion pipeline.
  *
  * We use this trait to expose both the store and a [[com.daml.network.environment.SpliceLedgerConnection]]
  * whose command submission calls wait for the store to have ingested their effects.
  *
  * We recommend using that connection for executing all command submissions that
  * depend on reads from the store to avoid synchronization issues like #4536
  */
trait AppStoreWithIngestion[Store <: AppStore] {

  /** The store setup with ingestion. */
  def store: Store

  /** A ledger connection whose command submission waits for ingestion into the store. */
  def connection: SpliceLedgerConnection
}
