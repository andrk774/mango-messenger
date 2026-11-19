package com.mango.messenger.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun ReferralSettings(viewModel:SettingsViewModel) {
    val state by viewModel.referral.collectAsStateWithLifecycle()
    val busy by viewModel.referralBusy.collectAsStateWithLifecycle()
    val error by viewModel.referralError.collectAsStateWithLifecycle()
    var code by remember {mutableStateOf("")}
    var editing by remember {mutableStateOf(false)}
    var ownCode by remember {mutableStateOf("")}
    val context=LocalContext.current
    LaunchedEffect(Unit) {viewModel.loadReferral()}
    Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Приглашайте друзей и коллег",style=MaterialTheme.typography.headlineSmall)
        Text("Вы и приглашённый получите по 7 дней Mango Premium. Все функции остаются бесплатными.")
        Text("Друг должен зарегистрироваться на том же сервере, подтвердить email и ввести ваш код здесь в течение 7 дней после регистрации. Код можно применить один раз. Самоприглашения и приглашения от более новых аккаунтов не принимаются.")
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state?.let {info ->
            OutlinedTextField(info.code,{},readOnly=true,label={Text("Ваш код")},modifier=Modifier.fillMaxWidth())
            TextButton(enabled=!busy,onClick={ownCode=info.code;viewModel.referralError.value=null;editing=true}) {Text("Изменить свой код")}
            Text("Приняли приглашение: ${info.invited}")
            Button(onClick={
                val text="Присоединяйся к Mango! После регистрации и подтверждения email открой Настройки → Реферальная программа и введи код ${info.code}. Мы получим по 7 дней Premium. Используй тот же сервер Mango, что и я."
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"Поделиться приглашением"))
            },modifier=Modifier.fillMaxWidth()) {Text("Поделиться приглашением")}
            if(info.applied) Text("Приглашение принято. Награда отражена в истории подписки.")
            else {
                HorizontalDivider()
                OutlinedTextField(code,{code=it.trim().uppercase().take(32)},label={Text("Код друга")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Button(enabled=!busy&&code.matches(Regex("[A-Z0-9_]{3,32}")),onClick={viewModel.loadReferral(code)},modifier=Modifier.fillMaxWidth()) {Text("Применить код")}
            }
        }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        TextButton(enabled=!busy,onClick={viewModel.loadReferral()}) {Text("Обновить")}
    }
    if(editing) AlertDialog(onDismissRequest={if(!busy)editing=false},title={Text("Ваш реферальный код")},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("От 3 до 32 латинских букв, цифр или подчёркиваний. Например, ANDREY774. После изменения старый код перестанет действовать. Начисленные награды сохранятся.")
            OutlinedTextField(ownCode,{ownCode=it.uppercase(java.util.Locale.ROOT).take(32)},label={Text("Новый код")},singleLine=true)
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }},confirmButton={TextButton(enabled=!busy&&ownCode.matches(Regex("[A-Z0-9_]{3,32}")),onClick={viewModel.editReferral(ownCode){editing=false}}){Text(if(busy)"Сохранение…" else "Сохранить")}},
        dismissButton={TextButton(enabled=!busy,onClick={editing=false}){Text("Отмена")}})
}
