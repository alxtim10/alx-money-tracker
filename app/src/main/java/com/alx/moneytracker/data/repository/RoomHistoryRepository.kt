package com.alx.moneytracker.data.repository

import com.alx.moneytracker.data.local.dao.CategoryDao
import com.alx.moneytracker.data.local.dao.TransactionDao
import com.alx.moneytracker.data.local.dao.WalletDao
import com.alx.moneytracker.data.local.toDomain
import com.alx.moneytracker.domain.Category
import com.alx.moneytracker.domain.Transaction
import com.alx.moneytracker.domain.Wallet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Room-backed [HistoryRepository] (Scope 2, read-only).
 *
 * Observation delegates directly to the existing DAO [Flow]s and maps each entity stream to its
 * domain equivalent via the mappers in `com.alx.moneytracker.data.local`, keeping domain models
 * free of Room types and reusing Scope 1's conventions (Requirement 12.1, AGENTS.md Data Rule 2).
 *
 * The class holds no write path: it reads through [TransactionDao.observeAll],
 * [WalletDao.observeActiveWallets], and [CategoryDao.observeAllActive] and never invokes any
 * mutating DAO method, so the read-only boundary (Requirement 12) is preserved structurally.
 *
 * The transactions stream maps each success into [Result.success] and catches any read error into
 * [Result.failure] so a failing read surfaces without terminating the stream (Requirements 1.7, 12.4).
 */
class RoomHistoryRepository(
    private val transactionDao: TransactionDao,
    private val walletDao: WalletDao,
    private val categoryDao: CategoryDao
) : HistoryRepository {

    override fun observeTransactions(): Flow<Result<List<Transaction>>> =
        transactionDao.observeAll()
            .map { entities -> Result.success(entities.map { it.toDomain() }) }
            .catch { cause -> emit(Result.failure(cause)) }

    override fun observeWallets(): Flow<List<Wallet>> =
        walletDao.observeActiveWallets().map { entities -> entities.map { it.toDomain() } }

    override fun observeCategories(): Flow<List<Category>> =
        categoryDao.observeAllActive().map { entities -> entities.map { it.toDomain() } }
}
