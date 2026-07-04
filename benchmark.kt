fun main() {
    val allDocs = (1..10000).map { Doc(it.toLong(), "folder/2023-01-0${it % 10}/file_$it.md", "file_$it.md") }
    val dates = (1..50).map { "2023-01-${(it % 10).toString().padStart(2, '0')}" }

    // Original
    val start1 = System.currentTimeMillis()
    for(i in 1..10) {
        val found = mutableListOf<String>()
        val notFound = mutableListOf<String>()
        for (date in dates) {
            val digits = date.replace("-", "")
            val matches = allDocs.filter { doc ->
                doc.relativePath.replace("/", "").replace("-", "").contains(digits)
            }.take(3)
            if (matches.isNotEmpty()) {
                found += matches.map { "[d=${it.id}] ${it.relativePath}" }
            } else {
                notFound += date
            }
        }
    }
    val time1 = System.currentTimeMillis() - start1
    println("Original time: $time1 ms")

    // Optimized
    val start2 = System.currentTimeMillis()
    for(i in 1..10) {
        val found = mutableListOf<String>()
        val notFound = mutableListOf<String>()
        val normalizedDocs = allDocs.map { doc ->
            doc to doc.relativePath.replace("/", "").replace("-", "")
        }
        for (date in dates) {
            val digits = date.replace("-", "")
            val matches = normalizedDocs.filter { (_, normalizedPath) ->
                normalizedPath.contains(digits)
            }.take(3)
            if (matches.isNotEmpty()) {
                found += matches.map { (doc, _) -> "[d=${doc.id}] ${doc.relativePath}" }
            } else {
                notFound += date
            }
        }
    }
    val time2 = System.currentTimeMillis() - start2
    println("Optimized time: $time2 ms")
}

data class Doc(val id: Long, val relativePath: String, val fileName: String)
