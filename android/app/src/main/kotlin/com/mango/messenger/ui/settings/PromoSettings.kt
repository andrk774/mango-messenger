package com.mango.messenger.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mango.core.common.Outcome
import com.mango.core.network.AccountFeaturesRemote
import com.mango.messenger.ui.tasks.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PromoViewModel @Inject constructor(private val remote:AccountFeaturesRemote):ViewModel(){
    var busy by mutableStateOf(false);private set
    var message by mutableStateOf<String?>(null);private set
    fun redeem(code:String,onSuccess:()->Unit){if(busy)return;viewModelScope.launch{
        busy=true;message=null
        try {when(val result=remote.redeemPromo(code.trim())){
            is Outcome.Success->{message=if(result.value.alreadyApplied)"Этот промокод уже использован вами. Повторного начисления нет." else "Начислено: ${result.value.points} баллов и ${result.value.days} дней Premium";onSuccess()}
            is Outcome.Failure->message=result.error.toUserMessage()
        }}finally{busy=false}
    }}
}
@Composable internal fun PromoSettings(onSuccess:()->Unit,vm:PromoViewModel=hiltViewModel()){
    var code by androidx.compose.runtime.saveable.rememberSaveable{mutableStateOf("")}
    Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Активировать промокод",style=MaterialTheme.typography.titleLarge)
        Text("Введите промокод проекта. Баллы и дни Premium будут добавлены к вашему аккаунту один раз. Для активации подтвердите email.")
        OutlinedTextField(code,{code=it.take(40)},label={Text("Промокод")},singleLine=true,enabled=!vm.busy,modifier=Modifier.fillMaxWidth())
        Button(onClick={vm.redeem(code,onSuccess)},enabled=!vm.busy&&code.trim().length>=4){Text(if(vm.busy)"Проверяем…" else "Активировать")}
        vm.message?.let{Text(it)}
    }
}
