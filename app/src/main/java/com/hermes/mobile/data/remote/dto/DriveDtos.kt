package com.hermes.mobile.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Google Drive REST API v3 (subset)

@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: String? = null,
    @SerialName("modifiedTime") val modifiedTime: String? = null,
    val parents: List<String>? = null,
    val thumbnailLink: String? = null,
    @SerialName("webViewLink") val webViewLink: String? = null,
    val trashed: Boolean = false
)

@Serializable
data class DriveFileList(
    val files: List<DriveFile>,
    @SerialName("nextPageToken") val nextPageToken: String? = null,
    @SerialName("incompleteSearch") val incompleteSearch: Boolean? = null
)

@Serializable
data class DrivePermission(
    val role: String,      // owner | writer | reader
    val type: String       // user | anyone
)
