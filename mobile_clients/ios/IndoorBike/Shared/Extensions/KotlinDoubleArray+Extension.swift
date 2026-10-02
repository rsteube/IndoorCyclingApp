import shared

extension KotlinDoubleArray {
    var doubles: [Double] { (0 ..< Int(size)).map { Double(get(index: Int32($0))) } }
}
