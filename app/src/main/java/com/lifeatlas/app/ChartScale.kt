package com.lifeatlas.app

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs

data class ChartScale(val minimum:Double,val maximum:Double,val offset:Double,val ticks:List<String>)
fun chartScale(values:List<Double>,includeZero:Boolean=false):ChartScale {
    require(values.isNotEmpty() && values.all{it.isFinite()})
    var lo=values.minOrNull()!!;var hi=values.maxOrNull()!!
    if(includeZero){lo=minOf(0.0,lo);hi=maxOf(0.0,hi)}
    if(lo==hi){val padding=if(lo==0.0)1.0 else abs(lo)*0.02;lo-=padding;hi+=padding}
    val span=hi-lo;val offset=if(abs(lo)>0 && span/abs(lo)<0.0001)lo else 0.0
    fun label(value:Double):String {val n=BigDecimal.valueOf(value-offset).round(MathContext(8)).stripTrailingZeros();return if(n.abs()>=BigDecimal("1000000") || (n.signum()!=0 && n.abs()<BigDecimal("0.0001")))n.toEngineeringString()else n.toPlainString()}
    return ChartScale(lo,hi,offset,(0..2).map{label(lo+span*it/2)})
}
