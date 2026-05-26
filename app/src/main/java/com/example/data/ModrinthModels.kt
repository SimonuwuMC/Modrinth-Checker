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
data class VersionFile(
    val url: String,
    val filename: String,
    val primary: Boolean? = false,
    val size: Int? = 0
)

@JsonClass(generateAdapter = true)
data class VersionResponse(
    val id: String,
    @Json(name = "project_id") val projectId: String? = null,
    val name: String,
    @Json(name = "version_number") val versionNumber: String,
    val changelog: String? = null,
    @Json(name = "date_published") val datePublished: String? = null,
    val downloads: Int? = 0,
    @Json(name = "version_type") val versionType: String? = "release", // "release", "beta", "alpha"
    @Json(name = "game_versions") val gameVersions: List<String>? = emptyList(),
    val loaders: List<String>? = emptyList(),
    val files: List<VersionFile>? = null
)

@JsonClass(generateAdapter = true)
data class SearchHit(
    @Json(name = "project_id") val projectId: String,
    @Json(name = "project_type") val projectType: String? = null,
    val slug: String,
    val author: String? = null,
    val title: String,
    val description: String? = null,
    @Json(name = "icon_url") val iconUrl: String? = null,
    val downloads: Int? = 0,
    @Json(name = "latest_version") val latestVersion: String? = null,
    @Json(name = "client_side") val clientSide: String? = null,
    @Json(name = "server_side") val serverSide: String? = null
)

@JsonClass(generateAdapter = true)
data class SearchResponse(
    val hits: List<SearchHit>,
    val offset: Int,
    val limit: Int,
    @Json(name = "total_hits") val totalHits: Int
)
