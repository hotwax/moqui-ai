package org.moqui.ai

import java.math.BigDecimal
import java.math.RoundingMode

/** Pure cost math. Prices are per 1,000,000 tokens. Returns a BigDecimal cost scaled to 6 dp. */
class CostCalc {
    static BigDecimal cost(long tokensIn, long tokensOut, BigDecimal inPricePerM, BigDecimal outPricePerM) {
        BigDecimal million = 1000000G
        BigDecimal inCost  = (inPricePerM ?: 0G)  * (tokensIn as BigDecimal)  / million
        BigDecimal outCost = (outPricePerM ?: 0G) * (tokensOut as BigDecimal) / million
        return (inCost + outCost).setScale(6, RoundingMode.HALF_UP)
    }
}
