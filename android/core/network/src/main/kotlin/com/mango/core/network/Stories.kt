package com.mango.core.network

import kotlinx.serialization.Serializable

@Serializable data class StoryDto(val id:String,val ownerId:String,val displayName:String,val createdAt:String,val expiresAt:String,val mine:Boolean)
@Serializable data class StoryImageDto(val image:String)
@Serializable data class StoryCreateDto(val id:String,val image:String)
