package com.onekey.updater.data.gitlab


data class GitLabRelease(
    val tag_name: String,
    val description: String,
    val assets: GitLabAssets,
    val author: GitLabAuthor
)
