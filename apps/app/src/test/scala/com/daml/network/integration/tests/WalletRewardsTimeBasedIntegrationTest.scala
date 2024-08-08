package com.daml.network.integration.tests

import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import com.daml.network.util.{SpliceUtil, TimeTestUtil, WalletTestUtil}
import com.daml.network.validator.automation.ReceiveFaucetCouponTrigger
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

class WalletRewardsTimeBasedIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      // TODO (#10859) remove and fix test failures
      .withAmuletPrice(walletAmuletPrice)

  // TODO (#10859) remove and fix test failures
  override def walletAmuletPrice = SpliceUtil.damlDecimal(1.0)

  "A wallet" should {

    "list and automatically collect app & validator rewards" in { implicit env =>
      val (alice, bob) = onboardAliceAndBob()
      waitForWalletUser(aliceValidatorWalletClient)
      waitForWalletUser(bobValidatorWalletClient)

      // Tap amulet and do a transfer from alice to bob
      aliceWalletClient.tap(walletAmuletToUsd(50))

      p2pTransfer(aliceWalletClient, bobWalletClient, bob, 40.0)

      // Retrieve transferred amulet in bob's wallet and transfer part of it back to alice;
      // bob's validator will receive some app rewards
      eventually()(bobWalletClient.list().amulets should have size 1)
      p2pTransfer(bobWalletClient, aliceWalletClient, alice, 30.0)

      val openRounds = eventually() {
        import math.Ordering.Implicits.*
        val openRounds = sv1ScanBackend
          .getOpenAndIssuingMiningRounds()
          ._1
          .filter(_.payload.opensAt <= env.environment.clock.now.toInstant)
        openRounds should not be empty
        openRounds
      }

      eventually() {
        bobValidatorWalletClient.listAppRewardCoupons() should have size 1
        bobValidatorWalletClient.listValidatorRewardCoupons() should have size 1
        aliceValidatorWalletClient.listAppRewardCoupons() should have size 1
        aliceValidatorWalletClient.listValidatorRewardCoupons() should have size 1
        bobValidatorWalletClient
          .listValidatorFaucetCoupons() should have size openRounds.size.toLong
        aliceValidatorWalletClient
          .listValidatorFaucetCoupons() should have size openRounds.size.toLong
      }

      // avoid messing with the computation of balance
      bobValidatorBackend.validatorAutomation
        .trigger[ReceiveFaucetCouponTrigger]
        .pause()
        .futureValue

      val prevBalance = bobValidatorWalletClient.balance().unlockedQty

      // Bob's validator collects rewards
      // it takes 3 ticks for the IssuingMiningRound 1 to be created and open.
      advanceRoundsByOneTick
      advanceRoundsByOneTick
      advanceRoundsByOneTick

      eventually()(bobValidatorWalletClient.listAppRewardCoupons() should have size 0)
      eventually()(bobValidatorWalletClient.listValidatorRewardCoupons() should have size 0)
      eventually()(bobValidatorWalletClient.listValidatorFaucetCoupons() should have size 0)

      val newBalance = bobValidatorWalletClient.balance().unlockedQty

      // We just check that the balance has increased by roughly the right amount,
      // rather then repeating the calculation for the reward amount
      // 2.85 USD per faucet coupon
      val faucetCouponAmountUsd = 2.85 * openRounds.size
      assertInRange(
        newBalance - prevBalance,
        (
          walletUsdToAmulet(-0.1 + faucetCouponAmountUsd),
          walletUsdToAmulet(0.5 + faucetCouponAmountUsd),
        ),
      )
    }
  }
}
