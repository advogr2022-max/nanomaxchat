package com.maxmini

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * HTTP-сервер (NanoHTTPD) — API для WebView.
 * Порт 8085, слушает localhost.
 */
class MaxHttpServer(context: android.content.Context, port: Int) : NanoHTTPD(port) {
    companion object {
        private const val TAG = "MaxHttpServer"
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
            append("""<input type="tel" id="phone" placeholder="+7XXXXXXXXXX"></div>""")
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

        if (method == Method.OPTIONS) {
            return corsResponse(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
        }

        // Статика
        if (uri == "/" || uri == "/login") return htmlResponse(HTML_LOGIN)
        if (uri == "/chat") return htmlResponse("<!DOCTYPE html><html><head><meta charset='utf-8'><title>MAX Chat</title></head><body><h1>Чат</h1><p>Заглушка</p></body></html>")

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
            }
            protocol.onConnectionLost = { cause ->
                AppState.connectionAlive = false
                AppState.connLogError("Соединение потеряно: $cause")
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

    private fun apiVerifyCode(session: IHTTPSession): Response {
        try {
            val data = JSONObject(parseBody(session))
            val code = data.optString("code", "").trim()
            if (code.isEmpty()) return jsonResponse(400, mapOf("ok" to false, "error" to "Укажите код"))

            AppState.connLog("SMS-код получен, передача протоколу...")

            // Передаём код через AppState (volatile bridge)
            AppState.provideAuthCode(code)

            // Также напрямую в протокол
            val protocol = AppState.protocol
            if (protocol != null) {
                runBlocking { protocol.provideAuthCode(code) }
            }

            // Ждём результат (busy wait с Thread.sleep)
            for (i in 0 until 1200) {
                if (AppState.isAuthenticated) {
                    AppState.connLog("Авторизация успешна!")
                    return jsonOk(mapOf("message" to "Авторизация успешна"))
                }
                if (AppState.connectError != null && !AppState.isConnecting) {
                    return jsonResponse(401, mapOf("ok" to false, "error" to (AppState.connectError ?: "Ошибка")))
                }
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
            return jsonResponse(504, mapOf("ok" to false, "error" to "Таймаут авторизации"))
        } catch (e: Exception) {
            return jsonResponse(400, mapOf("ok" to false, "error" to "Неверный JSON"))
        }
    }

    private fun apiLogout(): Response {
        connectJob?.cancel()
        connectJob = null
        runBlocking { AppState.protocol?.close() }
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
        val chatId = session.uri.removePrefix("/api/messages/").split("/").first().toLongOrNull() ?: return jsonResponse(400, mapOf("ok" to false, "error" to "Неверный chat_id"))
        val msgs = AppState.messagesCache[chatId] ?: emptyList()
        return jsonOk(mapOf("messages" to msgs))
    }

    private fun apiSendMessage(session: IHTTPSession): Response {
        if (!AppState.isAuthenticated) return jsonResponse(401, mapOf("ok" to false, "error" to "Не авторизован"))
        return jsonResponse(501, mapOf("ok" to false, "error" to "Not implemented"))
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
        val resp = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        return corsResponse(resp)
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
        val resp = newFixedLengthResponse(Response.Status.lookup(status), "application/json; charset=utf-8", json.toString())
        return corsResponse(resp)
    }

    private fun corsResponse(resp: Response): Response {
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS")
        resp.addHeader("Access-Control-Allow-Headers", "Content-Type,Authorization")
        return resp
    }

    override fun stop() {
        connectJob?.cancel()
        runBlocking { AppState.protocol?.close() }
        super.stop()
    }
}
