fun <T : Double> testDouble(d: Double, v: T, vararg va: T) {
    val first = va[0]
    d == va[0]
}
