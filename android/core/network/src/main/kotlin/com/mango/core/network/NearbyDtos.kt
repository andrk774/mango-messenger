package com.mango.core.network

import kotlinx.serialization.Serializable

@Serializable data class NearbyLocationDto(val latitude:Double,val longitude:Double)
@Serializable data class NearbyPersonDto(val id:String,val username:String,val displayName:String)
@Serializable data class NearbyVisibilityDto(val expiresAt:String?)
