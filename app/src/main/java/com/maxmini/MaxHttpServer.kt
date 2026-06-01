package com.maxmini

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * HTTP-сервер (NanoHTTPD) — API для WebView.
 * Порт 8085, слушает только localhost.
 */
class MaxHttpServer(context: android.content.Context, port: Int) : NanoHTTPD("127.0.0.1", port) {
    companion object {
        private const val TAG = "MaxHttpServer"
        private const val PORT = 8085

        private val HTML_LOGIN = buildString {
            append("""<!DOCTYPE html><html lang="ru"><head><meta charset="UTF-8">""")
            append("""<meta name="viewport" content="width=device-width,initial-scale=1.0">""")
            append("""<title>MAX Chat</title>""")
            append("""<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
background:linear-gradient(135deg,#0a0f1e,#1a2342);min-height:100vh;display:flex;align-items:center;justify-content:center;color:#e0e6f0}
.card{width:100%;max-width:400px;padding:20px}
.logo{text-align:center;font-size:28px;font-weight:700;margin-bottom:30px;
background:linear-gradient(135deg,#60a5fa,#a78bfa);-webkit-background-clip:text;-webkit-text-fill-color:transparent}
.form-group{margin-bottom:16px}
.form-group label{display:block;font-size:13px;color:#8892a8;margin-bottom:6px}
.form-group input{width:100%;padding:12px 16px;background:rgba(10,15,30,0.6);
border:1px solid rgba(100,116,170,0.25);border-radius:10px;color:#e0e6f0;font-size:15px;outline:none}
.form-group input:focus{border-color:#3b82f6}
.btn{width:100%;padding:14px;border:none;border-radius:10px;font-size:15px;font-weight:600;
cursor:pointer;transition:all 0.2s}
.btn-primary{background:linear-gradient(135deg,#3b82f6,#6366f1);color:#fff}
.btn-primary:disabled{opacity:0.5;cursor:not-allowed}
.btn-secondary{background:rgba(100,116,170,0.15);color:#8892a8;margin-top:12px}
.error-msg{background:rgba(239,68,68,0.1);border:1px solid rgba(239,68,68,0.3);
color:#f87171;padding:12px;border-radius:10px;margin-bottom:16px;display:none}
.error-msg.show{display:block}
.status-text{text-align:center;font-size:13px;color:#8892a8;margin-top:16px}
.status-text.success{color:#22c55e}.status-text.error{color:#f87171}
.spinner{display:inline-block;width:18px;height:18px;border:2px solid rgba(255,255,255,0.3);
border-top-color:#fff;border-radius:50%;animation:spin 0.6s linear infinite;margin-right:8px;vertical-align:middle}
@keyframes spin{to{transform:rotate(360deg)}}
.hidden{display:none!important}
</style></head><body><div class="card">""")
            append("""<div class="logo">MAX Chat</div>""")
            append("""<div class="error-msg" id="errorMsg"></div>""")
            append("""<div class="form-group"><label>Номер телефона</label>""")
            append("""<input type="tel" id="phone" placeholder="+7XXXXXXXXXX" value="+79000000000"></div>""")
            append("""<button class="btn btn-primary" id="sendBtn" onclick="sendCode()">Отправить SMS-код</button>""")
            append("""<div class="form-group hidden" id="codeGroup"><label>SMS-код</label>""")
            append("""<input type="text" id="code" placeholder="Введите код"></div>""")
            append("""<button class="btn btn-primary hidden" id="verifyBtn" onclick="verifyCode()">Подтвердить</button>""")
            append("""<button class="btn btn-secondary hidden" id="backBtn" onclick="back()">← Назад</button>""")
            append("""<div class="status-text" id="statusText"></div>""")
            append("""</div><script>
let timer;let phone='';
function qs(id){return document.getElementById(id)}
function api(m,p,b){return fetch(p,{method:m,headers:{'Content-Type':'application/json'},body:b?JSON.stringify(b):null}).then(r=>r.json())}
async function sendCode(){phone=qs('phone').value.trim();if(!phone)return showError('Введите номер');
hideError();const btn=qs('sendBtn');btn.disabled=true;btn.innerHTML='<span class="spinner"></span>Подключение...';
const r=await api('POST','/api/send-code',{phone});
if(!r.ok){showError(r.error||'Ошибка');btn.disabled=false;btn.textContent='Отправить SMS-код';return}
setStatus('Подключение...');startPoll()}
function startPoll(){if(timer)clearTimeout(timer);poll()}
async function poll(){try{const r=await api('GET','/api/status');
if(r.authenticated){setStatus('Успешно!','success');setTimeout(()=>location.href='/chat',1000);return}
if(r.waiting_for_code){setStatus('Введите SMS-код','success');
qs('codeGroup').classList.remove('hidden');qs('verifyBtn').classList.remove('hidden')}
if(r.error){setStatus('Ошибка: '+r.error,'error');return}
timer=setTimeout(poll,1500)}catch(e){timer=setTimeout(poll,3000)}}
async function verifyCode(){const code=qs('code').value.trim();if(!code)return showError('Введите код');
hideError();const btn=qs('verifyBtn');btn.disabled=true;btn.innerHTML='<span class="spinner"></span>Проверка...';
const r=await api('POST','/api/verify-code',{code});
if(r.ok){setStatus('Успешно!','success');setTimeout(()=>location.href='/chat',1000)}
else if(r.error==='2FA_REQUIRED'){showError('Требуется 2FA пароль')}
else{showError(r.error||'Ошибка');btn.disabled=false;btn.textContent='Подтвердить'}}
function back(){if(timer)clearTimeout(timer);qs('codeGroup').classList.add('hidden');qs('verifyBtn').classList.add('hidden')}
function showError(m){const e=qs('errorMsg');e.textContent=m;e.classList.add('show')}
function hideError(){qs('errorMsg').classList.remove('show')}
function setStatus(m,c){const s=qs('statusText');s.textContent=m;s.className='status-text '+(c||'')}
</script></body></html>""")
        }

        private val HTML_CHAT = buildString {
            append("""<!DOCTYPE html><html lang="ru"><head><meta charset="UTF-8">""")
            append("""<meta name="viewport" content="width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no">""")
            append("""<title>MAX Chat</title>""")
            append("""<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#0a0f1e;color:#e0e6f0;height:100vh;display:flex;flex-direction:column;overflow:hidden}
/* Header */
.header{background:linear-gradient(135deg,#1a2342,#2a3a5a);padding:14px 16px;display:flex;align-items:center;gap:12px;flex-shrink:0}
.header .back-btn{background:none;border:none;color:#8892a8;font-size:20px;cursor:pointer;padding:4px 8px;display:none}
.header .back-btn.show{display:block}
.header h1{font-size:17px;font-weight:600;flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.header .logout-btn{background:none;border:none;color:#f87171;font-size:13px;cursor:pointer;padding:4px 8px}
/* Chat list */
#chatList{flex:1;overflow-y:auto;padding:8px 0}
.chat-item{display:flex;align-items:center;padding:14px 16px;gap:12px;cursor:pointer;border-bottom:1px solid rgba(100,116,170,0.1);transition:background 0.15s}
.chat-item:active{background:rgba(59,130,246,0.1)}
.chat-avatar{width:44px;height:44px;border-radius:50%;background:linear-gradient(135deg,#3b82f6,#6366f1);display:flex;align-items:center;justify-content:center;font-size:18px;color:#fff;flex-shrink:0}
.chat-info{flex:1;min-width:0}
.chat-name{font-size:15px;font-weight:500;margin-bottom:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.chat-preview{font-size:13px;color:#6b7280;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.chat-time{font-size:11px;color:#4b5563;flex-shrink:0}
/* Messages */
#messageView{flex:1;display:none;flex-direction:column}
#messagesContainer{flex:1;overflow-y:auto;padding:12px 16px;display:flex;flex-direction:column;gap:6px}
.msg{max-width:82%;padding:10px 14px;border-radius:16px;font-size:14px;line-height:1.4;word-wrap:break-word}
.msg-out{background:linear-gradient(135deg,#3b82f6,#2563eb);color:#fff;align-self:flex-end;border-bottom-right-radius:4px}
.msg-in{background:rgba(30,41,59,0.8);color:#e0e6f0;align-self:flex-start;border-bottom-left-radius:4px}
.msg-time{font-size:10px;color:rgba(255,255,255,0.5);margin-top:4px;text-align:right}
.msg-in .msg-time{color:rgba(255,255,255,0.35)}
/* Input */
.input-bar{display:flex;gap:8px;padding:10px 12px;background:#1a2342;flex-shrink:0}
.input-bar input{flex:1;padding:10px 14px;background:rgba(10,15,30,0.6);border:1px solid rgba(100,116,170,0.25);border-radius:20px;color:#e0e6f0;font-size:15px;outline:none}
.input-bar input:focus{border-color:#3b82f6}
.input-bar button{width:40px;height:40px;border:none;border-radius:50%;background:linear-gradient(135deg,#3b82f6,#6366f1);color:#fff;font-size:18px;cursor:pointer;flex-shrink:0;display:flex;align-items:center;justify-content:center;transition:all 0.15s}
.input-bar button:disabled{opacity:0.4;cursor:not-allowed}
/* Spinner */
.loading{text-align:center;padding:40px;color:#6b7280;font-size:14px}
.empty-state{text-align:center;padding:60px 20px;color:#6b7280}
.empty-state .icon{font-size:48px;margin-bottom:12px}
</style></head><body>""")
            append("""<div class="header">""")
            append("""<button class="back-btn" id="backBtn" onclick="showChatList()">←</button>""")
            append("""<h1 id="headerTitle">Чаты</h1>""")
            append("""<button class="logout-btn" onclick="doLogout()">Выйти</button>""")
            append("""</div>""")
            append("""<div id="chatList"><div class="loading">Загрузка чатов...</div></div>""")
            append("""<div id="messageView">""")
            append("""<div id="messagesContainer"></div>""")
            append("""<div class="input-bar">""")
            append("""<input type="text" id="msgInput" placeholder="Сообщение..." onkeydown="if(event.key==='Enter')sendMsg()">""")
            append("""<button id="sendBtn" onclick="sendMsg()">➤</button>""")
            append("""</div></div>""")
            append("""<script>
let currentChatId=null;let pollTimer=null;let chatsData=[];
function qs(id){return document.getElementById(id)}
function api(m,p,b){return fetch(p,{method:m,headers:{'Content-Type':'application/json'},body:b?JSON.stringify(b):null}).then(r=>r.json())}
function escapeHtml(t){const d=document.createElement('div');d.textContent=t;return d.innerHTML}

async function loadChats(){try{
const r=await api('GET','/api/chats');
if(!r.ok){qs('chatList').innerHTML='<div class="empty-state"><div class="icon">💬</div><p>Ошибка загрузки</p></div>';return}
chatsData=r.chats||[];
if(chatsData.length===0){qs('chatList').innerHTML='<div class="empty-state"><div class="icon">💬</div><p>Нет чатов</p></div>';return}
let html='';
for(const c of chatsData){
const name=c.title||c.name||c.email||'Без имени';
const lastMsg=c.last_message_text||'';
const avatar=name.charAt(0).toUpperCase();
html+='<div class="chat-item" onclick="openChat('+(c.id||c.chat_id)+')">'+
'<div class="chat-avatar">'+escapeHtml(avatar)+'</div>'+
'<div class="chat-info"><div class="chat-name">'+escapeHtml(name)+'</div>'+
'<div class="chat-preview">'+escapeHtml(lastMsg.substring(0,60))+'</div></div>'+
'</div>'}
qs('chatList').innerHTML=html
}catch(e){qs('chatList').innerHTML='<div class="empty-state"><div class="icon">⚠️</div><p>Ошибка: '+e.message+'</p></div>'}}

async function openChat(chatId){currentChatId=chatId;
qs('chatList').style.display='none';qs('messageView').style.display='flex';
qs('backBtn').classList.add('show');
const chat=chatsData.find(c=>(c.id||c.chat_id)==chatId);
qs('headerTitle').textContent=chat?chat.title||chat.name||'Чат':'Чат';
qs('messagesContainer').innerHTML='<div class="loading">Загрузка...</div>';
try{
const r=await api('GET','/api/messages/'+chatId);
if(r.messages&&r.messages.length>0){
renderMessages(r.messages)}else{
qs('messagesContainer').innerHTML='<div class="empty-state"><div class="icon">💬</div><p>Нет сообщений</p></div>'}
}catch(e){qs('messagesContainer').innerHTML='<div class="empty-state">Ошибка</div>'}
startPoll()}
function showChatList(){currentChatId=null;
qs('chatList').style.display='block';qs('messageView').style.display='none';
qs('backBtn').classList.remove('show');qs('headerTitle').textContent='Чаты';
if(pollTimer){clearTimeout(pollTimer);pollTimer=null}
loadChats()}
function renderMessages(msgs){let html='';
for(const m of msgs){
const text=m.text||m.message||'';
const isOut=m.is_out||m.direction==='out'||m.sender_id===0;
const ts=m.created_at||m.timestamp||'';
const timeStr=ts?new Date(ts*1000).toLocaleTimeString('ru',{hour:'2-digit',minute:'2-digit'}):'';
html+='<div class="msg '+(isOut?'msg-out':'msg-in')+'">'+
escapeHtml(text)+
'<div class="msg-time">'+timeStr+'</div></div>'}
qs('messagesContainer').innerHTML=html;
qs('messagesContainer').scrollTop=qs('messagesContainer').scrollHeight}

function startPoll(){if(pollTimer)clearTimeout(pollTimer);pollMsg()}
async function pollMsg(){if(!currentChatId)return;
try{const r=await api('GET','/api/poll');
if(r.messages&&r.messages.length>0){
for(const m of r.messages){
const chatId=m.chat_id;
if(chatId==currentChatId){
const msgEl=document.createElement('div');
const text=m.text||m.message||'';
const isOut=m.is_out||false;
const timeStr='';
msgEl.className='msg '+(isOut?'msg-out':'msg-in');
msgEl.innerHTML=escapeHtml(text)+'<div class="msg-time">'+timeStr+'</div>';
qs('messagesContainer').appendChild(msgEl);
qs('messagesContainer').scrollTop=qs('messagesContainer').scrollHeight
}else if(chatsData.some(c=>(c.id||c.chat_id)==chatId)){
loadChats()}}}
pollTimer=setTimeout(pollMsg,2000)
}catch(e){pollTimer=setTimeout(pollMsg,3000)}}

async function sendMsg(){const input=qs('msgInput');const text=input.value.trim();
if(!text||!currentChatId)return;
input.disabled=true;qs('sendBtn').disabled=true;
try{const r=await api('POST','/api/send-message',{chat_id:currentChatId,text:text});
if(r.ok){input.value='';
const msgEl=document.createElement('div');
msgEl.className='msg msg-out';
msgEl.innerHTML=escapeHtml(text)+'<div class="msg-time">только что</div>';
qs('messagesContainer').appendChild(msgEl);
qs('messagesContainer').scrollTop=qs('messagesContainer').scrollHeight
}}catch(e){}
input.disabled=false;qs('sendBtn').disabled=false;input.focus()}

async function doLogout(){if(!confirm('Выйти из аккаунта?'))return;
await api('POST','/api/logout');location.href='/'}

loadChats();
</script></body></html>""")
        }
    }

    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectJob: Job? = null

    override fun serve(session: IHTTPSession): Response {
        return try { handleRequest(session) } catch (e: Exception) {
            Log.e(TAG, "serve: ${e.message}")
            jsonResponse(500, mapOf("ok" to false, "error" to "Internal error"))
        }
    }

    private fun handleRequest(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // Статика
        if (uri == "/" || uri == "/login") return htmlResponse(HTML_LOGIN)
        if (uri == "/chat") return htmlResponse(HTML_CHAT)

        // API
        return when {
            uri == "/api/status" && method == Method.GET -> apiStatus()
            uri == "/api/send-code" && method == Method.POST -> apiSendCode(session)
            uri == "/api/verify-code" && method == Method.POST -> apiVerifyCode(session)
            uri == "/api/logout" && method == Method.POST -> apiLogout()
            uri == "/api/chats" && method == Method.GET -> apiChats()
            uri.matches(Regex("/api/messages/\\d+")) && method == Method.GET -> apiMessages(session)
            uri == "/api/send-message" && method == Method.POST -> apiSendMessage(session)
            uri == "/api/poll" && method == Method.GET -> apiPoll()
            uri == "/api/log" && method == Method.GET -> apiLog(session)
            else -> jsonResponse(404, mapOf("ok" to false, "error" to "Not found"))
        }
    }

    private fun apiStatus(): Response {
        val waitingCode = AppState.isConnecting && !AppState.isAuthenticated
        val now = System.currentTimeMillis() / 1000
        val elapsed = if (AppState.smsSentAt > 0) (now - AppState.smsSentAt).toInt() else 0
        return jsonOk(mapOf(
            "authenticated" to AppState.isAuthenticated,
            "connecting" to AppState.isConnecting,
            "waiting_for_code" to waitingCode,
            "connection_alive" to AppState.connectionAlive,
            "error" to (AppState.connectError ?: ""),
            "sms_elapsed" to elapsed
        ))
    }

    private fun apiSendCode(session: IHTTPSession): Response {
        try {
            val data = JSONObject(parseBody(session))
            var phone = data.optString("phone", "").trim()
            if (phone.isEmpty()) return jsonResponse(400, mapOf("ok" to false, "error" to "Укажите номер"))
            if (!phone.startsWith("+")) phone = "+$phone"
            if (AppState.isConnecting) return jsonResponse(409, mapOf("ok" to false, "error" to "Уже выполняется подключение"))

            AppState.resetAuth()
            AppState.currentPhone = phone
            AppState.isConnecting = true
            AppState.connectError = null
            AppState.resetAuthEvent()
            AppState.smsSentAt = System.currentTimeMillis() / 1000
            AppState.connLog("Начинаем подключение к MAX для номера $phone")

            // Запускаем подключение в корутине
            connectJob?.cancel()
            connectJob = serverScope.launch { doConnect(phone) }
            return jsonOk(mapOf("message" to "Подключение запущено"))
        } catch (e: Exception) {
            return jsonResponse(400, mapOf("ok" to false, "error" to "Неверный JSON"))
        }
    }

    private suspend fun doConnect(phone: String) {
        try {
            val client = MaxTcpClient()
            val protocol = MaxProtocol(client)
            AppState.protocol = protocol

            protocol.onAuthenticated = { token ->
                AppState.isAuthenticated = true
                AppState.isConnecting = false
                AppState.connectionAlive = true
                AppState.connLog("Авторизация успешна!")
                // Загружаем чаты в кэш
                serverScope.launch {
                    try {
                        val chats = protocol.fetchChats()
                        AppState.chatsCache.clear()
                        AppState.chatsCache.addAll(chats)
                        AppState.connLog("Загружено ${chats.size} чатов")
                    } catch (e: Exception) {
                        AppState.connLogError("Ошибка загрузки чатов: ${e.message}")
                    }
                }
            }
            protocol.onConnectionLost = { cause ->
                AppState.connectionAlive = false
                AppState.connLogError("Соединение потеряно: $cause")
            }
            protocol.onMessage = { msg ->
                AppState.newMessages.add(msg)
                val chatId = (msg["chat_id"] as? Number)?.toLong()
                if (chatId != null) {
                    val msgs = AppState.messagesCache.computeIfAbsent(chatId) {
                        java.util.concurrent.CopyOnWriteArrayList()
                    }
                    msgs.add(msg)
                }
            }

            val ok = protocol.startAuth(phone)
            if (!ok) {
                AppState.isConnecting = false
                AppState.connectionAlive = false
                AppState.connectError = protocol.connectError
                AppState.connLogError("Ошибка авторизации: ${protocol.connectError}")
            }
        } catch (e: CancellationException) {
            AppState.connLog("Подключение отменено")
        } catch (e: Exception) {
            AppState.isConnecting = false
            AppState.isAuthenticated = false
            AppState.connectionAlive = false
            AppState.connectError = e.message
            AppState.connLogError("Ошибка: ${e.message}")
        }
    }

    /**
     * #2,#3: убран runBlocking и busy-wait.
     * Просто передаём код в протокол и возвращаем OK.
     * Клиент (JS) сам узнаёт результат через /api/status (polling).
     */
    private fun apiVerifyCode(session: IHTTPSession): Response {
        try {
            val data = JSONObject(parseBody(session))
            val code = data.optString("code", "").trim()
            if (code.isEmpty()) return jsonResponse(400, mapOf("ok" to false, "error" to "Укажите код"))

            AppState.connLog("SMS-код получен, передача протоколу...")

            // Единый механизм передачи кода — только через AppState.provideAuthCode
            AppState.provideAuthCode(code)

            // Протокол читает authCode из AppState в waitForAuthCode()
            return jsonOk(mapOf("message" to "Код передан, ожидайте авторизацию"))
        } catch (e: Exception) {
            return jsonResponse(400, mapOf("ok" to false, "error" to "Неверный JSON"))
        }
    }

    private fun apiLogout(): Response {
        connectJob?.cancel()
        connectJob = null
        // #2: убран runBlocking — просто запускаем корутину
        serverScope.launch { AppState.protocol?.close() }
        AppState.resetAuth()
        AppState.protocol = null
        AppState.connLog("Выход выполнен")
        return jsonOk(emptyMap())
    }

    private fun apiChats(): Response {
        if (!AppState.isAuthenticated) return jsonResponse(401, mapOf("ok" to false, "error" to "Не авторизован"))
        val chats = AppState.chatsCache.toList()
        return jsonOk(mapOf("chats" to chats))
    }

    private fun apiMessages(session: IHTTPSession): Response {
        if (!AppState.isAuthenticated) return jsonResponse(401, mapOf("ok" to false, "error" to "Не авторизован"))
        val chatId = session.uri.removePrefix("/api/messages/").split("/").first().toLongOrNull()
            ?: return jsonResponse(400, mapOf("ok" to false, "error" to "Неверный chat_id"))

        // Проверяем кэш
        val cached = AppState.messagesCache[chatId]
        if (cached != null && cached.isNotEmpty()) {
            return jsonOk(mapOf("messages" to cached.toList()))
        }

        // Если нет в кэше — запрашиваем у протокола асинхронно, возвращаем что есть
        val protocol = AppState.protocol
        if (protocol != null && AppState.isAuthenticated) {
            serverScope.launch {
                try {
                    val msgs = protocol.fetchHistory(chatId)
                    val list = AppState.messagesCache.computeIfAbsent(chatId) {
                        java.util.concurrent.CopyOnWriteArrayList()
                    }
                    list.clear()
                    list.addAll(msgs)
                } catch (e: Exception) {
                    AppState.connLogError("Ошибка загрузки истории: ${e.message}")
                }
            }
        }

        return jsonOk(mapOf("messages" to (cached?.toList() ?: emptyList<Any>())))
    }

    private fun apiSendMessage(session: IHTTPSession): Response {
        if (!AppState.isAuthenticated) return jsonResponse(401, mapOf("ok" to false, "error" to "Не авторизован"))
        try {
            val data = JSONObject(parseBody(session))
            val chatId = data.optLong("chat_id", -1)
            val text = data.optString("text", "").trim()
            if (chatId < 0 || text.isEmpty()) return jsonResponse(400, mapOf("ok" to false, "error" to "Неверные параметры"))

            val protocol = AppState.protocol
            if (protocol == null) return jsonResponse(503, mapOf("ok" to false, "error" to "Нет соединения"))

            // Запускаем отправку в корутине
            serverScope.launch {
                try {
                    protocol.sendMessage(chatId, text)
                    AppState.connLog("Сообщение отправлено в чат $chatId")
                } catch (e: Exception) {
                    AppState.connLogError("Ошибка отправки: ${e.message}")
                }
            }
            return jsonOk(mapOf("message" to "Отправляется"))
        } catch (e: Exception) {
            return jsonResponse(400, mapOf("ok" to false, "error" to "Неверный JSON"))
        }
    }

    private fun apiPoll(): Response {
        val msgs = AppState.newMessages.toList()
        AppState.newMessages.clear()
        return jsonOk(mapOf("messages" to msgs))
    }

    private fun apiLog(session: IHTTPSession): Response {
        val after = session.parameters.getOrDefault("after", listOf("0")).first().toIntOrNull() ?: 0
        val log = AppState.connLog
        return jsonOk(mapOf(
            "log" to (if (after < log.size) log.subList(after, log.size) else emptyList<String>()),
            "total" to log.size
        ))
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun parseBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    private fun htmlResponse(html: String): Response {
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun jsonOk(data: Map<String, Any?>): Response {
        return jsonResponse(200, mapOf("ok" to true) + data)
    }

    private fun jsonResponse(status: Int, data: Map<String, Any?>): Response {
        val json = JSONObject()
        for ((k, v) in data) {
            when (v) {
                null -> json.put(k, JSONObject.NULL)
                is String -> json.put(k, v)
                is Number -> json.put(k, v)
                is Boolean -> json.put(k, v)
                is List<*> -> json.put(k, JSONArray(v))
                is Map<*, *> -> json.put(k, JSONObject(v as Map<String, Any?>))
                else -> json.put(k, v.toString())
            }
        }
        return newFixedLengthResponse(Response.Status.lookup(status), "application/json; charset=utf-8", json.toString())
    }

    override fun stop() {
        connectJob?.cancel()
        serverScope.launch { AppState.protocol?.close() }
        super.stop()
    }
}
