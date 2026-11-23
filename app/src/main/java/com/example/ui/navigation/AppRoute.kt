package com.example.ui.navigation

import com.example.core.model.AppModule
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute {
    @Serializable data object Dashboard : AppRoute
    @Serializable data object FamilyManagement : AppRoute
    @Serializable data object Income : AppRoute
    @Serializable data object Expense : AppRoute
    @Serializable data object Loans : AppRoute
    @Serializable data object Savings : AppRoute
    @Serializable data object Investments : AppRoute
    @Serializable data object Budgets : AppRoute
    @Serializable data object Analytics : AppRoute
    @Serializable data object Reports : AppRoute
    @Serializable data object FamilyChat : AppRoute
    @Serializable data object FamilyMeet : AppRoute
    @Serializable data object Notifications : AppRoute
    @Serializable data object Settings : AppRoute
    @Serializable data object Security : AppRoute
    @Serializable data object AuthStatus : AppRoute
    @Serializable data object ModulesHub : AppRoute
    @Serializable data object MemberCards : AppRoute
    @Serializable data object FamilyDecisions : AppRoute
}

fun AppModule.toRoute(): AppRoute = when (this) {
    AppModule.AUTHENTICATION -> AppRoute.AuthStatus
    AppModule.FAMILY_MANAGEMENT -> AppRoute.FamilyManagement
    AppModule.DASHBOARD -> AppRoute.Dashboard
    AppModule.INCOME -> AppRoute.Income
    AppModule.EXPENSE -> AppRoute.Expense
    AppModule.LOANS -> AppRoute.Loans
    AppModule.SAVINGS -> AppRoute.Savings
    AppModule.INVESTMENTS -> AppRoute.Investments
    AppModule.BUDGETS -> AppRoute.Budgets
    AppModule.ANALYTICS -> AppRoute.Analytics
    AppModule.REPORTS -> AppRoute.Reports
    AppModule.FAMILY_CHAT -> AppRoute.FamilyChat
    AppModule.FAMILY_MEET -> AppRoute.FamilyMeet
    AppModule.NOTIFICATIONS -> AppRoute.Notifications
    AppModule.SETTINGS -> AppRoute.Settings
    AppModule.SECURITY -> AppRoute.Security
    AppModule.MEMBER_CARDS -> AppRoute.MemberCards
    AppModule.FAMILY_DECISIONS -> AppRoute.FamilyDecisions
}
