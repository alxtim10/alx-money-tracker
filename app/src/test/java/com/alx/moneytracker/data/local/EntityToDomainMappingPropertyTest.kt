package com.alx.moneytracker.data.local

import com.alx.moneytracker.data.local.entity.CategoryEntity
import com.alx.moneytracker.data.local.entity.QuickPresetEntity
import com.alx.moneytracker.data.local.entity.WalletEntity
import com.alx.moneytracker.domain.TransactionType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property-based test for the entity-to-domain [toDomain] mappers (Task 7.11).
 *
 * Exactly one property test covering Property 12, using kotest-property with a minimum
 * of 100 iterations. This targets pure logic: the [WalletEntity.toDomain],
 * [CategoryEntity.toDomain], and [QuickPresetEntity.toDomain] functions in
 * [com.alx.moneytracker.data.local.Mappers].
 *
 * The generators draw arbitrary entities across the full field space:
 * - wallet: id, name (including surrounding whitespace), balance, is_default, is_archived;
 * - category: id, name, type (drawn from [TransactionType] and stored as the enum `name`
 *   so `toDomain()`'s `valueOf` resolves), a non-null icon string, is_archived;
 * - preset: id, amount, label.
 *
 * The mapping is a faithful projection: every field is carried over unchanged. The
 * assertions restate each field's correspondence rather than the whole `data class`
 * equality so a regression names the offending field. Category `icon` is a non-null
 * `String` in both the entity and the domain model, so it is asserted preserved as-is.
 *
 * Validates: Requirements 2.3, 8.3, 12.3
 */
class EntityToDomainMappingPropertyTest : FunSpec({

    val walletEntities: Arb<WalletEntity> = Arb.bind(
        Arb.long(0L..1_000_000L),
        // Include surrounding whitespace: the mapper preserves the stored name verbatim.
        Arb.string(0..20).map { "  $it  " },
        Arb.long(0L..999_999_999_999L),
        Arb.boolean(),
        Arb.boolean()
    ) { id, name, balance, isDefault, isArchived ->
        WalletEntity(
            id = id,
            name = name,
            balance = balance,
            isDefault = isDefault,
            isArchived = isArchived
        )
    }

    val categoryEntities: Arb<CategoryEntity> = Arb.bind(
        Arb.long(0L..1_000_000L),
        Arb.string(0..20),
        // Store the enum name so toDomain()'s TransactionType.valueOf resolves faithfully.
        Arb.enum<TransactionType>().map { it.name },
        Arb.string(0..12),
        Arb.boolean()
    ) { id, name, typeName, icon, isArchived ->
        CategoryEntity(
            id = id,
            name = name,
            type = typeName,
            icon = icon,
            isArchived = isArchived
        )
    }

    val presetEntities: Arb<QuickPresetEntity> = Arb.bind(
        Arb.long(0L..1_000_000L),
        Arb.long(0L..999_999_999_999L),
        Arb.string(0..20)
    ) { id, amount, label ->
        QuickPresetEntity(id = id, amount = amount, label = label)
    }

    // Feature: customization-metadata, Property 12: Entity-to-domain mapping is faithful
    test("Property 12: Entity-to-domain mapping is faithful") {
        checkAll(
            PropTestConfig(iterations = 100),
            walletEntities,
            categoryEntities,
            presetEntities
        ) { walletEntity, categoryEntity, presetEntity ->
            // Wallet: name / balance / is_default / is_archived preserved (plus id).
            val wallet = walletEntity.toDomain()
            wallet.id shouldBe walletEntity.id
            wallet.name shouldBe walletEntity.name
            wallet.balance shouldBe walletEntity.balance
            wallet.isDefault shouldBe walletEntity.isDefault
            wallet.isArchived shouldBe walletEntity.isArchived

            // Category: name / type / icon / is_archived preserved (plus id). The stored
            // type name round-trips through TransactionType.valueOf back to the same enum.
            val category = categoryEntity.toDomain()
            category.id shouldBe categoryEntity.id
            category.name shouldBe categoryEntity.name
            category.type shouldBe TransactionType.valueOf(categoryEntity.type)
            category.icon shouldBe categoryEntity.icon
            category.isArchived shouldBe categoryEntity.isArchived

            // Preset: amount / label preserved (plus id).
            val preset = presetEntity.toDomain()
            preset.id shouldBe presetEntity.id
            preset.amount shouldBe presetEntity.amount
            preset.label shouldBe presetEntity.label
        }
    }
})
