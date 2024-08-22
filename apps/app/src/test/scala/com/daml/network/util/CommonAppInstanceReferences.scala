package com.daml.network.util

import com.daml.network.config.SpliceInstanceNamesConfig
import com.daml.network.console.{
  AppManagerAppClientReference,
  ScanAppBackendReference,
  ScanAppClientReference,
  SplitwellAppBackendReference,
  SplitwellAppClientReference,
  SvAppBackendReference,
  SvAppClientReference,
  ValidatorAppBackendReference,
  ValidatorAppClientReference,
  WalletAppClientReference,
}
import com.daml.network.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.daml.network.console.AnsExternalAppClientReference
import com.digitalasset.canton.DomainAlias
import org.slf4j.LoggerFactory

// TODO(#736): these should eventually be defined analogue to Canton's `participant1` references etc
// however, this is likely only possible once we depend on Canton as a library
trait CommonAppInstanceReferences {
  private val logger = LoggerFactory.getLogger(getClass)

  def decentralizedSynchronizerId(implicit env: SpliceTestConsoleEnvironment): DomainId =
    sv1Backend.participantClientWithAdminToken.domains.id_of(sv1Backend.config.domains.global.alias)
  def decentralizedSynchronizerAlias(implicit env: SpliceTestConsoleEnvironment): DomainAlias =
    sv1Backend.config.domains.global.alias

  def dsoParty(implicit env: SpliceTestConsoleEnvironment): PartyId = sv1ScanBackend.getDsoPartyId()

  def sv1Backend(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference = svb("sv1")

  def sv1LocalBackend(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference = svb(
    "sv1Local"
  )

  def sv2Backend(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference = svb("sv2")

  def sv2LocalBackend(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference = svb(
    "sv2Local"
  )

  def sv2OnboardedBackend(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference = svb(
    "sv2Onboarded"
  )

  def sv3Backend(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference = svb("sv3")

  def sv3LocalBackend(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference = svb(
    "sv3Local"
  )

  def sv4Backend(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference = svb("sv4")

  def sv4LocalBackend(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference = svb(
    "sv4Local"
  )

  def sv1Client(implicit env: SpliceTestConsoleEnvironment): SvAppClientReference = sv_client("sv1")

  def sv1ScanBackend(implicit env: SpliceTestConsoleEnvironment): ScanAppBackendReference = scanb(
    "sv1Scan"
  )

  def sv1ScanLocalBackend(implicit env: SpliceTestConsoleEnvironment): ScanAppBackendReference =
    scanb(
      "sv1ScanLocal"
    )

  def sv2ScanBackend(implicit env: SpliceTestConsoleEnvironment): ScanAppBackendReference = scanb(
    "sv2Scan"
  )

  def aliceWalletClient(implicit env: SpliceTestConsoleEnvironment): WalletAppClientReference = uwc(
    "aliceWallet"
  )

  def aliceAppManagerClient(implicit
      env: SpliceTestConsoleEnvironment
  ): AppManagerAppClientReference = uamc(
    "aliceAppManager"
  )

  def aliceValidatorWalletClient(implicit
      env: SpliceTestConsoleEnvironment
  ): WalletAppClientReference = wc(
    "aliceValidatorWallet"
  )

  def aliceValidatorWalletLocalClient(implicit
      env: SpliceTestConsoleEnvironment
  ): WalletAppClientReference = wc(
    "aliceValidatorLocalWallet"
  )

  def aliceValidatorBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "aliceValidator"
  )

  def aliceValidatorLocalBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "aliceValidatorLocal"
  )

  def aliceValidatorClient(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppClientReference =
    vc(
      "aliceValidatorClient"
    )

  def bobWalletClient(implicit env: SpliceTestConsoleEnvironment): WalletAppClientReference = uwc(
    "bobWallet"
  )

  // Note: this uses `wc` instead of `uwc` because we don't suffix the user names of SVs.
  def sv1WalletClient(implicit env: SpliceTestConsoleEnvironment): WalletAppClientReference = wc(
    "sv1Wallet"
  )

  def sv1WalletLocalClient(implicit env: SpliceTestConsoleEnvironment): WalletAppClientReference =
    wc(
      "sv1WalletLocal"
    )

  def bobValidatorWalletClient(implicit
      env: SpliceTestConsoleEnvironment
  ): WalletAppClientReference = wc(
    "bobValidatorWallet"
  )

  def charlieWalletClient(implicit env: SpliceTestConsoleEnvironment): WalletAppClientReference =
    uwc(
      "charlieWallet"
    )

  def bobValidatorBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "bobValidator"
  )

  def sv1ValidatorBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "sv1Validator"
  )

  def sv1ValidatorLocalBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "sv1ValidatorLocal"
  )

  def sv2ValidatorBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "sv2Validator"
  )

  def sv2ValidatorLocalBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "sv2ValidatorLocal"
  )

  def sv3ValidatorBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "sv3Validator"
  )

  def sv4ValidatorBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "sv4Validator"
  )

  def splitwellValidatorBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference =
    v(
      "splitwellValidator"
    )

  def splitwellWalletClient(implicit
      env: SpliceTestConsoleEnvironment
  ): WalletAppClientReference =
    wc(
      "splitwellProviderWallet"
    )

  def aliceAnsExternalClient(implicit
      env: SpliceTestConsoleEnvironment
  ): AnsExternalAppClientReference = rdpe(
    "aliceAns"
  )

  def bobAnsExternalClient(implicit
      env: SpliceTestConsoleEnvironment
  ): AnsExternalAppClientReference = rdpe(
    "bobAns"
  )

  def charlieAnsExternalClient(implicit
      env: SpliceTestConsoleEnvironment
  ): AnsExternalAppClientReference = rdpe(
    "charlieAns"
  )

  def aliceSplitwellClient(implicit
      env: SpliceTestConsoleEnvironment
  ): SplitwellAppClientReference = rsw(
    "aliceSplitwell"
  )

  def bobSplitwellClient(implicit
      env: SpliceTestConsoleEnvironment
  ): SplitwellAppClientReference = rsw(
    "bobSplitwell"
  )

  def charlieSplitwellClient(implicit
      env: SpliceTestConsoleEnvironment
  ): SplitwellAppClientReference = rsw(
    "charlieSplitwell"
  )

  def splitwellBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): SplitwellAppBackendReference = sw(
    "providerSplitwellBackend"
  )

  def svb(name: String)(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference =
    env.svs.local
      .find(_.name == name)
      .getOrElse(sys.error(s"sv [$name] not configured"))

  def sv_client(name: String)(implicit env: SpliceTestConsoleEnvironment): SvAppClientReference =
    env.svs.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"sv [$name] not configured"))

  // "user wallet client"; we define this separately from wc so we can override it more conveniently
  def uwc(name: String)(implicit env: SpliceTestConsoleEnvironment): WalletAppClientReference = wc(
    name
  )

  // "user app manager client"
  def uamc(name: String)(implicit env: SpliceTestConsoleEnvironment): AppManagerAppClientReference =
    env.appManagers
      .find(_.name == name)
      .getOrElse(sys.error(s"app manager [$name] not configured"))

  def wc(name: String)(implicit env: SpliceTestConsoleEnvironment): WalletAppClientReference =
    env.wallets
      .find(_.name == name)
      .getOrElse(sys.error(s"wallet [$name] not configured"))

  def v(name: String)(implicit env: SpliceTestConsoleEnvironment): ValidatorAppBackendReference =
    env.validators.local
      .find(_.name == name)
      .getOrElse(sys.error(s"validator [$name] not configured"))

  def vc(name: String)(implicit env: SpliceTestConsoleEnvironment): ValidatorAppClientReference =
    env.validators.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"validator client [$name] not configured"))

  def rdpe(
      name: String
  )(implicit env: SpliceTestConsoleEnvironment): AnsExternalAppClientReference =
    env.externalAns
      .find(_.name == name)
      .getOrElse(sys.error(s"remote external ANS [$name] not configured"))

  def sw(
      name: String
  )(implicit env: SpliceTestConsoleEnvironment): SplitwellAppBackendReference =
    env.splitwells.local
      .find(_.name == name)
      .getOrElse(sys.error(s"local splitwell [$name] not configured"))

  def rsw(
      name: String
  )(implicit env: SpliceTestConsoleEnvironment): SplitwellAppClientReference =
    env.splitwells.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"remote splitwell [$name] not configured"))

  def scanb(
      name: String
  )(implicit env: SpliceTestConsoleEnvironment): ScanAppBackendReference =
    env.scans.local
      .find(_.name == name)
      .getOrElse(sys.error(s"scan app [$name] not configured"))

  def scancl(
      name: String
  )(implicit env: SpliceTestConsoleEnvironment): ScanAppClientReference =
    env.scans.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"scan app client [$name] not configured"))

  def spliceInstanceNames(implicit env: SpliceTestConsoleEnvironment): SpliceInstanceNamesConfig = {
    // Find any SV reference that contains splice instance names
    env.svs.local.headOption
      .map(_.config.spliceInstanceNames)
      .getOrElse(
        // TODO(#13480): figure out how to not rely on this default for runbook preflight tests
        {
          val enableCnInstanceNames = sys.env.getOrElse("ENABLE_CN_INSTANCE_NAMES", "false")
          val useCnInstanceNames =
            try { enableCnInstanceNames.toBoolean }
            catch {
              case _: IllegalArgumentException => {
                logger.warn(
                  s"ENABLE_CN_INSTANCE_NAMES had the value $enableCnInstanceNames whhich could not be parsed as a boolean: defaulting to false"
                )
                false
              }
            }

          if (useCnInstanceNames)
            SpliceInstanceNamesConfig(
              networkName = "Canton Network",
              networkFaviconUrl = "https://www.canton.network/hubfs/cn-favicon-05%201-1.png",
              amuletName = "Canton Coin",
              amuletNameAcronym = "CC",
              nameServiceName = "Canton Name Service",
              nameServiceNameAcronym = "CNS",
            )
          else
            SpliceInstanceNamesConfig(
              networkName = "Splice",
              networkFaviconUrl = "https://www.hyperledger.org/hubfs/hyperledgerfavicon.png",
              amuletName = "Amulet",
              amuletNameAcronym = "AMT",
              nameServiceName = "Amulet Name Service",
              nameServiceNameAcronym = "ANS",
            )
        }
      )
  }

  def ansAcronym(implicit env: SpliceTestConsoleEnvironment): String =
    spliceInstanceNames.nameServiceNameAcronym.toLowerCase()
}
