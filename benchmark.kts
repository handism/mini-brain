import kotlin.system.measureNanoTime
import kotlin.random.Random

data class VecResult(val score: Float, val second: Chunk)
data class Chunk(val docId: Long)
data class Bm25Result(val docId: Long)

val vecResults = List(100000) { VecResult(0.5f, Chunk(Random.nextLong(1, 50000))) }
val bm25Results = List(100000) { Bm25Result(Random.nextLong(1, 50000)) }

// Warmup
for (i in 1..10) {
    (vecResults.map { it.second.docId } + bm25Results.map { it.docId }).distinct()
    (vecResults.asSequence().map { it.second.docId } + bm25Results.asSequence().map { it.docId }).distinct().toList()
}

val time1 = measureNanoTime {
    for (i in 1..100) {
        val r = (vecResults.map { it.second.docId } + bm25Results.map { it.docId }).distinct()
    }
}

val time2 = measureNanoTime {
    for (i in 1..100) {
        val r = (vecResults.asSequence().map { it.second.docId } + bm25Results.asSequence().map { it.docId }).distinct().toList()
    }
}

println("Original: ${time1 / 1000000} ms")
println("Sequence: ${time2 / 1000000} ms")
