package org.fossify.contacts.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.contacts.sync.crypto.Crypto
import org.fossify.contacts.sync.crypto.RecoveryCode
import org.fossify.contacts.sync.crypto.VaultCrypto
import org.fossify.contacts.sync.engine.SyncManifest
import org.fossify.contacts.sync.model.ContactPayload
import org.fossify.contacts.sync.model.Merger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 交叉校验：这些期望值由服务端的 test/vectors.ts 生成
 * （server/test/vectors.expected.json）。
 *
 * 只要有一条对不上，Android 和服务端参考实现就已经分叉了，
 * 两台设备之间的数据必然解不开。任何改动 Crypto / VaultCrypto 的 PR
 * 都必须先让这个测试通过。
 *
 * 跑法：./gradlew connectedCoreDebugAndroidTest
 * （需要真机或模拟器，因为 Argon2 是 JNI 实现的）
 */
@RunWith(AndroidJUnit4::class)
class CryptoVectorsTest {

    private val salt = Crypto.fromHex("000102030405060708090a0b0c0d0e0f")
    private val dek = Crypto.fromHex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
    private val recoveryKey = Crypto.fromHex("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f")
    private val uuid = "11111111-2222-3333-4444-555555555555"

    @Test
    fun argon2id_matchesServerVector() {
        val mk = VaultCrypto.deriveMasterKey("correct horse battery staple", salt)
        assertEquals(
            "853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e",
            Crypto.toHex(mk),
        )
    }

    @Test
    fun hkdfDerivations_matchServerVectors() {
        val mk = VaultCrypto.deriveMasterKey("correct horse battery staple", salt)
        assertEquals(
            "e07eecc9166b24f2f4e4e294248cb12b7da8b08e6209dea3d951a519d0bfa1ca",
            Crypto.toHex(VaultCrypto.deriveKek(mk, salt)),
        )
        assertEquals(
            "4aa78e28ce458092f0541343b0ba904541488ac2fc6b9f397e3d7c32dd6f50c3",
            VaultCrypto.deriveAuthSecret(mk, salt),
        )
        assertEquals(
            "d7ffc1c3e583edf0c3993a2dd2ab6cba86022cedc216e621c18f1725706feb79",
            Crypto.toHex(VaultCrypto.deriveRecoveryKek(recoveryKey, salt)),
        )
        assertEquals(
            "9fce60649c350b60bcd047d2fcfae91360a14ac4119c5a874cbe3cb812ff0f20",
            Crypto.toHex(VaultCrypto.deriveRecordKey(dek, uuid)),
        )
        assertEquals(
            "1472197bb3d90ded672e2fb104f06ed1689d97b6ef6fad2b848625a13a7a8d19",
            Crypto.toHex(VaultCrypto.deriveIndexKey(dek, salt)),
        )
    }

    @Test
    fun recordAad_matchesServerVector() {
        assertEquals(
            "111111112222333344445555555555550000000701",
            Crypto.toHex(VaultCrypto.recordAad(uuid, 7, 1)),
        )
    }

    @Test
    fun blindIndexAndBlobId_matchServerVectors() {
        val indexKey = VaultCrypto.deriveIndexKey(dek, salt)
        assertEquals(
            "e8a0b7d9596c6d33da487ac705a5e179",
            VaultCrypto.blindIndex(indexKey, "+8613800138000"),
        )
        assertEquals(
            "c597efb8dbab44f112d90f12bd1721e19e5ae1d63f2d690c2d8f6a9ceb3c114e",
            VaultCrypto.blobId(dek, "AAAA".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun itemId_matchesServerVectors() {
        assertEquals("a8e579e94ef8bcc266a9de2988dbfada", VaultCrypto.itemId("phones", "+8613800138000"))
        assertEquals("a4101691260cfeb21c09e90cd0c2ef95", VaultCrypto.itemId("groups", "家人"))
    }

    @Test
    fun recoveryCode_matchesServerVector() {
        assertEquals(
            "810M-4GT4-8N34-EJ29-995M-RKAE-9X85-2MJK-AHAN-CNTR-B5D5-PQ2X-BSFG-S8N4",
            RecoveryCode.format(recoveryKey),
        )
    }

    @Test
    fun recoveryCode_roundTripsAndToleratesTypos() {
        val code = RecoveryCode.format(recoveryKey)
        assertTrue(RecoveryCode.parse(code).contentEquals(recoveryKey))
        // 小写、空格代替连字符、把 O 当 0 输，都应该能解析回来
        val messy = code.lowercase().replace("-", " ")
        assertTrue(RecoveryCode.parse(messy).contentEquals(recoveryKey))
    }

    @Test
    fun recoveryCode_rejectsWrongChecksum() {
        val code = RecoveryCode.format(recoveryKey)
        val broken = (if (code[0] == '0') '1' else '0') + code.substring(1)
        try {
            RecoveryCode.parse(broken)
            fail("改过的恢复码应该被校验位挡下")
        } catch (e: RecoveryCode.RecoveryCodeException) {
            // 符合预期
        }
    }

    @Test
    fun padding_roundTripsAndAlignsTo256() {
        for (size in intArrayOf(0, 1, 5, 255, 256, 257, 1000)) {
            val data = ByteArray(size) { it.toByte() }
            val padded = Crypto.pad(data)
            assertEquals(0, padded.size % Crypto.PAD_BLOCK)
            assertTrue("size=$size 时长度没有增长", padded.size > size)
            assertTrue("size=$size 时还原失败", Crypto.unpad(padded).contentEquals(data))
        }
    }

    @Test
    fun record_roundTrips() {
        val payload = ContactPayload(
            first = "张三",
            surname = "",
            phones = listOf(
                ContactPayload.PhoneItem(
                    id = VaultCrypto.itemId("phones", "+8613800138000"),
                    value = "+86 138 0013 8000", norm = "+8613800138000",
                    type = 2, label = "", primary = false,
                )
            ),
        )
        val sealed = VaultCrypto.encryptRecord(dek, uuid, 3, payload.toCanonicalJson())
        val decoded = ContactPayload.fromJson(VaultCrypto.decryptRecord(dek, uuid, 3, sealed))
        assertEquals(payload, decoded)
        assertEquals(payload.toCanonicalJson(), decoded.toCanonicalJson())
    }

    @Test
    fun record_wrongRevFailsAuthentication() {
        val sealed = VaultCrypto.encryptRecord(dek, uuid, 3, ContactPayload(first = "李四").toCanonicalJson())
        try {
            VaultCrypto.decryptRecord(dek, uuid, 4, sealed)
            fail("换了 rev 之后应该认证失败，否则挡不住回滚攻击")
        } catch (e: Exception) {
            // 符合预期
        }
    }

    @Test
    fun record_tamperingIsDetected() {
        val sealed = VaultCrypto.encryptRecord(dek, uuid, 1, ContactPayload(first = "王五").toCanonicalJson())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0xff).toByte()
        try {
            VaultCrypto.decryptRecord(dek, uuid, 1, sealed)
            fail("篡改过的密文应该被 GCM 标签发现")
        } catch (e: Exception) {
            // 符合预期
        }
    }

    @Test
    fun canonicalJson_isStableRegardlessOfListOrder() {
        val a = ContactPayload.PhoneItem(VaultCrypto.itemId("phones", "111"), "111", "111", 2, "", false)
        val b = ContactPayload.PhoneItem(VaultCrypto.itemId("phones", "222"), "222", "222", 2, "", false)
        assertEquals(
            ContactPayload(phones = listOf(a, b)).toCanonicalJson(),
            ContactPayload(phones = listOf(b, a)).toCanonicalJson(),
        )
    }

    // ------------------------------------------------------------ 合并

    @Test
    fun merge_isIdempotentAndSymmetric() {
        val base = ContactPayload(first = "张三", company = "旧公司")
        val local = base.copy(first = "张三丰")
        val remote = base.copy(company = "新公司")

        val merged = Merger.merge(base, local, remote).merged
        assertEquals("张三丰", merged.first)
        assertEquals("新公司", merged.company)
        assertEquals(0, Merger.merge(base, local, remote).conflicts.size)

        // 对称：换个方向合并结果必须一样，否则两台设备会互相推来推去
        assertEquals(
            merged.toCanonicalJson(),
            Merger.merge(base, remote, local).merged.toCanonicalJson(),
        )
        // 幂等：合完再合一次不变
        assertEquals(
            merged.toCanonicalJson(),
            Merger.merge(merged, merged, merged).merged.toCanonicalJson(),
        )
    }

    @Test
    fun merge_flagsRealFieldConflicts() {
        val base = ContactPayload(notes = "")
        val local = base.copy(notes = "本机写的")
        val remote = base.copy(notes = "远端写的")
        val result = Merger.merge(base, local, remote)
        assertTrue("同字段双改必须被标记出来", result.conflicts.contains("notes"))
        // 裁决必须确定，两台设备各自算要得到同一个结果
        assertEquals(result.merged.notes, Merger.merge(base, remote, local).merged.notes)
    }

    @Test
    fun merge_localDeleteIsNotResurrectedByRemote() {
        val phone = ContactPayload.PhoneItem(
            VaultCrypto.itemId("phones", "+8613800138000"),
            "+8613800138000", "+8613800138000", 2, "", false,
        )
        val base = ContactPayload(first = "张三", phones = listOf(phone))
        val local = base.copy(phones = emptyList())
        val merged = Merger.merge(base, local, base).merged
        assertTrue("本机删掉的号码不该被远端旧副本复活", merged.phones.isEmpty())
    }

    @Test
    fun merge_sameNumberFromTwoDevicesDeduplicates() {
        val base = ContactPayload(first = "张三")
        val id = VaultCrypto.itemId("phones", "+8615000150000")
        val local = base.copy(
            phones = listOf(ContactPayload.PhoneItem(id, "+8615000150000", "+8615000150000", 2, "", false))
        )
        val remote = base.copy(
            phones = listOf(ContactPayload.PhoneItem(id, "+8615000150000", "+8615000150000", 2, "手机", false))
        )
        val merged = Merger.merge(base, local, remote).merged
        assertEquals("两台设备录入同一个号码只应留一条", 1, merged.phones.size)
    }

    // ------------------------------------------------------------ 同步清单

    @Test
    fun manifest_encodingMatchesServerVector() {
        val encoded = SyncManifest.encode(
            mapOf(
                "11111111-2222-3333-4444-555555555555" to 7,
                "00000000-0000-4000-8000-000000000001" to 1,
            )
        )
        assertEquals("RlNZTQEAAAACAAAAAAAAQACAAAAAAAAAAQAAAAERERERIiIzM0REVVVVVVVVAAAABw==", encoded)
    }

    @Test
    fun manifest_roundTripsAndIsOrderIndependent() {
        val entries = mapOf(
            "11111111-2222-3333-4444-555555555555" to 7,
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" to 1,
            "00000000-0000-4000-8000-000000000001" to 42,
        )
        val encoded = SyncManifest.encode(entries)
        assertEquals(entries, SyncManifest.decode(encoded))
        // 插入顺序不同也必须编出同样的字节，否则每次同步都会有"假改动"
        assertEquals(encoded, SyncManifest.encode(entries.entries.reversed().associate { it.key to it.value }))
    }

    @Test
    fun manifest_detectsHiddenRecord() {
        val manifest = mapOf("11111111-2222-3333-4444-555555555555" to 3)
        val issues = SyncManifest.verify(manifest, manifestRev = 2, lastKnownRev = 2, presentRevs = emptyMap())
        assertEquals(1, issues.size)
        assertTrue("服务器藏掉记录必须被发现", issues[0] is SyncManifest.Issue.Missing)
    }

    @Test
    fun manifest_detectsRollback() {
        val uuid = "11111111-2222-3333-4444-555555555555"
        val issues = SyncManifest.verify(
            manifest = mapOf(uuid to 5),
            manifestRev = 2,
            lastKnownRev = 2,
            presentRevs = mapOf(uuid to 3),
        )
        assertTrue("单条记录被退回旧版本必须被发现", issues.any { it is SyncManifest.Issue.Rollback })
    }

    @Test
    fun manifest_detectsManifestRollback() {
        val issues = SyncManifest.verify(emptyMap(), manifestRev = 1, lastKnownRev = 5, presentRevs = emptyMap())
        assertTrue("整份清单被退回必须被发现", issues.any { it is SyncManifest.Issue.ManifestRollback })
    }

    @Test
    fun manifest_absenceOnlyMattersAfterFirstWrite() {
        // 第一次同步时没有清单是正常的
        assertTrue(SyncManifest.verifyAbsence(0).isEmpty())
        // 见过之后突然没了就是被藏了
        assertTrue(SyncManifest.verifyAbsence(3).isNotEmpty())
    }

    @Test
    fun manifest_refusesToTruncateWhenOverLimit() {
        val tooMany = (0..SyncManifest.MAX_ENTRIES).associate {
            "00000000-0000-4000-8000-%012x".format(it) to 1
        }
        try {
            SyncManifest.encode(tooMany)
            fail("超出上限必须报错，静默截断等于把完整性保护关掉了")
        } catch (e: SyncManifest.TooManyRecords) {
            // 符合预期
        }
    }

    @Test
    fun normalizeNumber_isStableAcrossFormatting() {
        val expected = "+8613800138000"
        for (raw in listOf("+86 138 0013 8000", "+86-138-0013-8000", "+86(138)00138000", "+8613800138000")) {
            assertEquals(expected, ContactPayload.normalizeNumber(raw))
        }
        // 没有国家码的写法归一化后确实不同 —— 这是已知限制，见设计文档
        assertNotEquals(expected, ContactPayload.normalizeNumber("13800138000"))
    }
}
