package com.mango.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight

/** Native artwork follows both themes and remains crisp at every display scale. */
@Composable
fun MangoArtwork(modifier:Modifier=Modifier) {
    val colors=MaterialTheme.colorScheme
    Box(modifier.size(176.dp),contentAlignment=Alignment.Center) {
        Box(Modifier.size(152.dp).rotate(-10f).background(Brush.linearGradient(listOf(colors.primaryContainer,colors.secondaryContainer)),RoundedCornerShape(48.dp)))
        Surface(modifier=Modifier.size(100.dp).rotate(-8f),shape=RoundedCornerShape(30.dp),color=colors.primary,shadowElevation=6.dp) {
            Box(contentAlignment=Alignment.Center) {Icon(Icons.AutoMirrored.Filled.Chat,null,Modifier.size(54.dp),tint=colors.onPrimary)}
        }
        Surface(modifier=Modifier.align(Alignment.BottomEnd).padding(12.dp).size(54.dp),shape=RoundedCornerShape(18.dp),color=colors.tertiaryContainer,shadowElevation=4.dp) {
            Box(contentAlignment=Alignment.Center){Icon(Icons.Default.Check,null,Modifier.size(30.dp),tint=colors.onTertiaryContainer)}
        }
    }
}

@Composable
fun MangoWelcomeBanner(title:String,subtitle:String,onClick:()->Unit,modifier:Modifier=Modifier) {
    Card(onClick=onClick,modifier=modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer),shape=RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.primary) {
                Text("m",Modifier.padding(horizontal=14.dp,vertical=6.dp),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text(title,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onPrimaryContainer)
                Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text("→",style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
