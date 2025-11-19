package com.chipprbots.ethereum.vm

object Fixtures {

  val ConstantinopleBlockNumber = 200
  val PetersburgBlockNumber = 400
  val PhoenixBlockNumber = 600
  val IstanbulBlockNumber = 600
  val MagnetoBlockNumber = 700
  val BerlinBlockNumber = 700
  val MystiqueBlockNumber = 800
  val SpiralBlockNumber = 900

  val blockchainConfig: BlockchainConfigForEvm = BlockchainConfigForEvm(
    // block numbers are irrelevant
    frontierBlockNumber = 0,
    homesteadBlockNumber = 0,
    eip150BlockNumber = 0,
    eip160BlockNumber = 0,
    eip161BlockNumber = 0,
    byzantiumBlockNumber = 0,
    constantinopleBlockNumber = ConstantinopleBlockNumber,
    istanbulBlockNumber = IstanbulBlockNumber,
    maxCodeSize = Some(24576),
    accountStartNonce = 0,
    atlantisBlockNumber = 0,
    aghartaBlockNumber = 0,
    petersburgBlockNumber = PetersburgBlockNumber,
    phoenixBlockNumber = PhoenixBlockNumber,
    magnetoBlockNumber = MagnetoBlockNumber,
    berlinBlockNumber = BerlinBlockNumber,
    mystiqueBlockNumber = MystiqueBlockNumber,
    spiralBlockNumber = SpiralBlockNumber,
    chainId = 0x3d.toByte
  )

}
