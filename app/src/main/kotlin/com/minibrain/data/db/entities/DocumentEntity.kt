package com.minibrain.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val treeUri: String,
    val fileUri: String,
    val fileName: String,
    val relativePath: String,
    val lastModified: Long,
    val contentHash: String,
    @ColumnInfo(name = "headings") val headings: String? = null,
    @ColumnInfo(name = "first_para") val firstParagraph: String? = null,
    @ColumnInfo(name = "tags") val tags: String? = null,
    @ColumnInfo(name = "documentDate") val documentDate: String? = null,
)
