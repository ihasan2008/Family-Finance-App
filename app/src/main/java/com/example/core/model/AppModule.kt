package com.example.core.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Diversity3
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.VideoCall
import androidx.compose.ui.graphics.vector.ImageVector

enum class ModuleCategory(val title: String) {
    OVERVIEW("Overview"),
    CASH_FLOW("Cash Flow"),
    WEALTH_DEBT("Wealth & Debt"),
    PLANNING_INSIGHTS("Planning & Insights"),
    FAMILY_COLLAB("Family & Collaboration"),
    SYSTEM_SECURITY("System & Security")
}

enum class ModulePhaseStatus(val label: String) {
    ACTIVE("Live Foundation"),
    SAMPLE_PREVIEW("Sample Preview"),
    PLACEHOLDER("Planned Module")
}

enum class AppModule(
    val number: Int,
    val title: String,
    val shortLabel: String,
    val subtitle: String,
    val category: ModuleCategory,
    val status: ModulePhaseStatus,
    val icon: ImageVector,
    val plannedFeatures: List<String>
) {
    AUTHENTICATION(
        number = 1,
        title = "Authentication",
        shortLabel = "Auth",
        subtitle = "Mobile+Password, WebAuthn Passkeys, SMS OTP & Google Sign-In",
        category = ModuleCategory.SYSTEM_SECURITY,
        status = ModulePhaseStatus.ACTIVE,
        icon = Icons.Outlined.VerifiedUser,
        plannedFeatures = listOf(
            "Mobile + scrypt Password & SMS OTP Verification",
            "WebAuthn / FIDO2 Passkeys (Biometrics, Face & PIN)",
            "New-device verification & remote session revocation"
        )
    ),
    FAMILY_MANAGEMENT(
        number = 2,
        title = "Family Management",
        shortLabel = "Family",
        subtitle = "Expiring invitations, Admin approvals, roles & ownership",
        category = ModuleCategory.FAMILY_COLLAB,
        status = ModulePhaseStatus.ACTIVE,
        icon = Icons.Outlined.Diversity3,
        plannedFeatures = listOf(
            "Random Family ID permanently linked to Admin UID",
            "Expiring invitation tokens with Admin approval",
            "Member removal, role changes, ownership transfer & recovery"
        )
    ),
    DASHBOARD(
        number = 3,
        title = "Dashboard",
        shortLabel = "Home",
        subtitle = "Live family balance, net worth & cash-flow overview",
        category = ModuleCategory.OVERVIEW,
        status = ModulePhaseStatus.ACTIVE,
        icon = Icons.Outlined.Dashboard,
        plannedFeatures = listOf(
            "Real-time household net worth & liquid balance",
            "Filtered income, expense, savings & investment KPIs",
            "Live budget health, savings goals & loan snapshots"
        )
    ),
    INCOME(
        number = 4,
        title = "Income",
        shortLabel = "Income",
        subtitle = "Salary, business, freelancing, rental & recurring inflows",
        category = ModuleCategory.CASH_FLOW,
        status = ModulePhaseStatus.ACTIVE,
        icon = Icons.Outlined.ArrowDownward,
        plannedFeatures = listOf(
            "6 income categories with decimal-safe minor-unit precision",
            "Search, category, date & member filters with details sheet",
            "Recurring income schedules & duplicate-safe Firestore sync"
        )
    ),
    EXPENSE(
        number = 5,
        title = "Expense",
        shortLabel = "Expense",
        subtitle = "Food, housing, medical, bills & shared family spending",
        category = ModuleCategory.CASH_FLOW,
        status = ModulePhaseStatus.ACTIVE,
        icon = Icons.Outlined.ArrowUpward,
        plannedFeatures = listOf(
            "9 expense categories with decimal-safe minor-unit tracking",
            "Add, edit & delete with confirmation dialogs & audit trail",
            "Recurring bills & offline Room SQLite + Firestore sync"
        )
    ),
    LOANS(
        number = 6,
        title = "Loans",
        shortLabel = "Loans",
        subtitle = "Borrowed & lent tracking, installments & repayment history",
        category = ModuleCategory.WEALTH_DEBT,
        status = ModulePhaseStatus.ACTIVE,
        icon = Icons.Outlined.AccountBalance,
        plannedFeatures = listOf(
            "Borrowed & lent ledger with borrower/lender info",
            "Installment schedule & remaining balance precision",
            "Repayment history isolated from ordinary income/expense"
        )
    ),
    SAVINGS(
        number = 7,
        title = "Savings",
        shortLabel = "Savings",
        subtitle = "Savings goals, deposits, withdrawals & milestone progress",
        category = ModuleCategory.WEALTH_DEBT,
        status = ModulePhaseStatus.ACTIVE,
        icon = Icons.Outlined.Savings,
        plannedFeatures = listOf(
            "Goal progress tracking toward target amounts & dates",
            "Balance-safe deposits & withdrawals (not double-counted as income)",
            "Chronological savings transfer history"
        )
    ),
    INVESTMENTS(
        number = 8,
        title = "Investments",
        shortLabel = "Invest",
        subtitle = "Holdings, principal cost basis, separate profit & loss",
        category = ModuleCategory.WEALTH_DEBT,
        status = ModulePhaseStatus.ACTIVE,
        icon = Icons.AutoMirrored.Outlined.TrendingUp,
        plannedFeatures = listOf(
            "Principal separated from unrealized profit & loss",
            "Multi-asset portfolio valuation & performance history",
            "Decimal-safe Firestore & Room offline synchronization"
        )
    ),
    BUDGETS(
        number = 9,
        title = "Budgets",
        shortLabel = "Budgets",
        subtitle = "Monthly & category limits, live spending & threshold alerts",
        category = ModuleCategory.PLANNING_INSIGHTS,
        status = ModulePhaseStatus.ACTIVE,
        icon = Icons.Outlined.PieChart,
        plannedFeatures = listOf(
            "Overall monthly & category-level spending caps",
            "Automatic threshold alerts from verified expense ledger",
            "Month-over-month budget history without double counting"
        )
    ),
    ANALYTICS(
        number = 10,
        title = "Analytics",
        shortLabel = "Analytics",
        subtitle = "Interactive charts, cash flow, net worth & member insights",
        category = ModuleCategory.PLANNING_INSIGHTS,
        status = ModulePhaseStatus.ACTIVE,
        icon = Icons.Outlined.Analytics,
        plannedFeatures = listOf(
            "Monthly & yearly income vs. expense and cash flow charts",
            "Expense categories, member-wise spend & savings growth",
            "Investment performance with separated profit & loss"
        )
    ),
    REPORTS(
        number = 11,
        title = "Reports",
        shortLabel = "Reports",
        subtitle = "Encrypted offline financial statements & summaries",
        category = ModuleCategory.PLANNING_INSIGHTS,
        status = ModulePhaseStatus.ACTIVE,
        icon = Icons.Outlined.Assessment,
        plannedFeatures = listOf(
            "AES-256-GCM encrypted offline report snapshots",
            "Income, expense, loans, savings, investments & budget rollups",
            "Decimal-safe statement copy & instant offline refresh"
        )
    ),
    FAMILY_CHAT(
        number = 12,
        title = "Family Chat",
        shortLabel = "Chat",
        subtitle = "Offline-first encrypted family chat & record notes",
        category = ModuleCategory.FAMILY_COLLAB,
        status = ModulePhaseStatus.ACTIVE,
        icon = Icons.AutoMirrored.Outlined.Chat,
        plannedFeatures = listOf(
            "Cached chat history readable completely offline",
            "Offline message queue auto-synced when connectivity returns",
            "Admin chat toggle & strict familyId membership isolation"
        )
    ),
    FAMILY_MEET(
        number = 13,
        title = "Family Meet",
        shortLabel = "Meet",
        subtitle = "1-to-1 WebRTC calls & LiveKit group video meetings",
        category = ModuleCategory.FAMILY_COLLAB,
        status = ModulePhaseStatus.ACTIVE,
        icon = Icons.Outlined.VideoCall,
        plannedFeatures = listOf(
            "1-to-1 P2P WebRTC audio & video calls with PiP and ICE recovery",
            "LiveKit SFU group meetings with participant grid, speaker & screen share",
            "Host moderation controls, invite links, schedules & call history"
        )
    ),
    NOTIFICATIONS(
        number = 14,
        title = "Notifications",
        shortLabel = "Alerts",
        subtitle = "Real-time messages, calls, meetings, budgets & FCM push",
        category = ModuleCategory.FAMILY_COLLAB,
        status = ModulePhaseStatus.ACTIVE,
        icon = Icons.Outlined.Notifications,
        plannedFeatures = listOf(
            "Instant alerts for family messages, calls & meetings",
            "Loan due dates, budget warnings & savings milestones",
            "Firebase Cloud Messaging push notifications with unread badges"
        )
    ),
    SETTINGS(
        number = 15,
        title = "Settings",
        shortLabel = "Settings",
        subtitle = "Theme mode, local cache & account preferences",
        category = ModuleCategory.SYSTEM_SECURITY,
        status = ModulePhaseStatus.ACTIVE,
        icon = Icons.Outlined.Settings,
        plannedFeatures = listOf(
            "Light, Dark & System theme switching",
            "Local Room database & Firestore sync status",
            "Google account session & sign-out controls"
        )
    ),
    SECURITY(
        number = 16,
        title = "Security",
        shortLabel = "Security",
        subtitle = "Zero-trust family isolation & session protection",
        category = ModuleCategory.SYSTEM_SECURITY,
        status = ModulePhaseStatus.ACTIVE,
        icon = Icons.Outlined.Lock,
        plannedFeatures = listOf(
            "Firestore rule-enforced family isolation",
            "UID & Family ID boundary verification",
            "Biometric passkey & device session protection"
        )
    ),
    MEMBER_CARDS(
        number = 17,
        title = "Member Cards",
        shortLabel = "Cards",
        subtitle = "Digital Member Cards, 16-char IDs & individual ledgers",
        category = ModuleCategory.FAMILY_COLLAB,
        status = ModulePhaseStatus.ACTIVE,
        icon = Icons.Outlined.Badge,
        plannedFeatures = listOf(
            "Digital Member Cards with 16-char alphanumeric Member IDs",
            "Individual balance, income, expense, loan, savings & investment ledgers",
            "Privacy controls without exposing phone, password, or DOB"
        )
    ),
    FAMILY_DECISIONS(
        number = 18,
        title = "Approvals & Decisions",
        shortLabel = "Approvals",
        subtitle = "Proposals, family voting rules & atomic financial execution",
        category = ModuleCategory.FAMILY_COLLAB,
        status = ModulePhaseStatus.ACTIVE,
        icon = Icons.Outlined.HowToVote,
        plannedFeatures = listOf(
            "Proposals for loans, investments, savings withdrawals & large purchases",
            "Majority, unanimous & configurable quorum voting with audit history",
            "Once-only atomic execution into household accounts upon approval"
        )
    )
}
