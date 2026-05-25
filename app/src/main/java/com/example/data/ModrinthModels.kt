package com.example.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ProjectResponse(
    val id: String,
    val slug: String,
    val title: String,
    val description: String,
    @Json(name = "icon_url") val iconUrl: String?,
    val downloads: Int,
    @Json(name = "client_side") val clientSide: String,
    @Json(name = "server_side") val serverSide: String
)

@JsonClass(generateAdapter = true)
data class VersionResponse(
    val id: String,
    @Json(name = "project_id") val projectId: String,
    val name: String,
    @Json(name = "version_number") val versionNumber: String,
    val changelog: String?,
    @Json(name = "date_published") val datePublished: String,
    val downloads: Int,
    @Json(name = "version_type") val versionType: String, // "release", "beta", "alpha"
    @Json(name = "game_versions") val gameVersions: List<String>,
    val loaders: List<String>
)
