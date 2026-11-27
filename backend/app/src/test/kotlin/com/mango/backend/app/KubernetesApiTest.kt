package com.mango.backend.app

import com.sun.net.httpserver.HttpsServer
import com.sun.net.httpserver.HttpsConfigurator
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.*

class KubernetesApiTest {
    @TempDir lateinit var dir:Path
    @Test fun `reject arbitrary targets and unsafe names`() {
        fun target(url:String,ns:String="mango-dev")=KubernetesTarget("local",url,ns,"backend","backend",dir.resolve("token").toString(),dir.resolve("ca").toString())
        for(url in listOf("http://localhost","https://user:password@localhost","https://localhost/other","https://localhost/?url=other","https://localhost/#fragment"))
            assertFailsWith<IllegalArgumentException>{target(url)}
        assertFailsWith<IllegalArgumentException>{target("https://localhost","../secrets")}
        assertEquals("mango-dev",target("https://localhost:6443").namespace)
    }
    @Test fun `TLS token rotation projection redirect and denied access`() {
        val store=dir.resolve("test.p12")
        val process=ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
            "-genkeypair","-alias","test","-keyalg","RSA","-keystore",store.toString(),"-storetype","PKCS12",
            "-storepass","testpassword","-keypass","testpassword","-dname","CN=localhost","-ext","SAN=dns:localhost","-validity","1","-noprompt")
            .redirectErrorStream(true).start()
        process.inputStream.readAllBytes();assertEquals(0,process.waitFor())
        val ks=KeyStore.getInstance("PKCS12").apply{Files.newInputStream(store).use{load(it,"testpassword".toCharArray())}}
        val ca=dir.resolve("ca.crt");Files.write(ca,ks.getCertificate("test").encoded)
        val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply{init(ks,"testpassword".toCharArray())}
        val tls=SSLContext.getInstance("TLS").apply{init(km.keyManagers,null,null)}
        val server=HttpsServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        server.httpsConfigurator=HttpsConfigurator(tls)
        var expected="first-token";var responseCode=200
        server.createContext("/"){x->
            val code=if(x.requestHeaders.getFirst("Authorization")!="Bearer $expected")401 else responseCode
            val body=when {
                code!=200 -> """{"message":"must-not-leak-server-details"}"""
                x.requestURI.path.endsWith("/deployments/backend")->"""{"metadata":{"generation":1},"spec":{"replicas":1,"template":{"spec":{"secret":"must-not-leak"}}},"status":{"replicas":1,"readyReplicas":1,"availableReplicas":1,"observedGeneration":1}}"""
                x.requestURI.path.endsWith("/services/backend")->"""{"spec":{"type":"ClusterIP","clusterIP":"private"}}"""
                else->"""{"metadata":{},"items":[]}"""
            }.toByteArray()
            if(code==302)x.responseHeaders.add("Location","https://localhost/credentials")
            x.sendResponseHeaders(code,body.size.toLong());x.responseBody.use{it.write(body)}
        }
        server.start()
        try {
            val token=dir.resolve("token");Files.writeString(token,expected)
            val config=dir.resolve("connections.json")
            fun configure(host:String="localhost") {Files.writeString(config,buildJsonArray{add(buildJsonObject{
                put("context","local");put("server","https://$host:${server.address.port}");put("namespace","mango-dev")
                put("deployment","backend");put("service","backend");put("tokenFile",token.toString());put("caFile",ca.toString())
            })}.toString())}
            configure();val api=KubernetesApi(config)
            assertFalse(api.publicTargets().toString().contains("tokenFile"))
            val status=api.status("local")
            assertEquals(1,status["deployment"]!!.jsonObject["readyReplicas"]!!.jsonPrimitive.int)
            assertFalse(status.toString().contains("must-not-leak"))
            expected="rotated-token";Files.writeString(token,expected);api.status("local")
            Files.writeString(token,"expired")
            assertFalse(assertFails{api.status("local")}.message.orEmpty().contains("must-not-leak-server-details"))
            Files.writeString(token,expected)
            responseCode=302;assertFails{api.status("local")}
            responseCode=403;assertFails{api.status("local")}
            responseCode=200;configure("127.0.0.1");assertFails{api.status("local")} // hostname verification remains enabled
            configure();Files.writeString(ca,"invalid certificate");assertFails{api.status("local")}
            assertFails{api.status("unknown")}
        }finally{server.stop(0)}
    }
}
