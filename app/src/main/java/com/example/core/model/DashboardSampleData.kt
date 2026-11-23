package com.example.core.model

data class SampleMonthlyFlow(
    val month: String,
    val income: Float,
    val expense: Float
)

data class SampleBudgetItem(
    val category: String,
    val spent: Double,
    val limit: Double
) {
    val progress: Float get() = if (limit > 0) (spent / limit).toFloat().coerceIn(0f, 1.2f) else 0f
}

data class SampleSavingsGoal(
    val title: String,
    val currentAmount: Double,
    val targetAmount: Double,
    val targetDate: String
) {
    val progress: Float get() = if (targetAmount > 0) (currentAmount / targetAmount).toFloat().coerceIn(0f, 1f) else 0f
}

data class SampleLoanSummary(
    val title: String,
    val remainingBalance: Double,
    val monthlyDue: Double,
    val dueDateLabel: String
)

data class SampleActivityItem(
    val title: String,
    val memberName: String,
    val amountFormatted: String,
    val isPositive: Boolean,
    val category: String,
    val timeAgo: String
)

object DashboardSampleData {
    const val SAMPLE_LABEL = "Sample Preview Data"

    const val NET_WORTH = "$148,920"
    const val NET_WORTH_DELTA = "+4.8% vs last month"

    const val MONTHLY_INCOME = "$9,450"
    const val MONTHLY_INCOME_DELTA = "+6.2%"

    const val MONTHLY_EXPENSE = "$5,180"
    const val MONTHLY_EXPENSE_DELTA = "-3.1%"

    const val MONTHLY_SAVINGS = "$4,270"
    const val SAVINGS_RATE = "45.2% rate"

    const val TOTAL_INVESTMENTS = "$84,300"
    const val ACTIVE_LOANS_BALANCE = "$18,450"

    val monthlyCashFlow = listOf(
        SampleMonthlyFlow("Jun", 8600f, 5400f),
        SampleMonthlyFlow("Jul", 8900f, 5750f),
        SampleMonthlyFlow("Aug", 9100f, 5200f),
        SampleMonthlyFlow("Sep", 8850f, 5600f),
        SampleMonthlyFlow("Oct", 9200f, 4950f),
        SampleMonthlyFlow("Nov", 9450f, 5180f)
    )

    val budgets = listOf(
        SampleBudgetItem("Housing & Utilities", 2100.0, 2250.0),
        SampleBudgetItem("Groceries & Dining", 940.0, 1100.0),
        SampleBudgetItem("Education & Childcare", 680.0, 750.0),
        SampleBudgetItem("Transport & Fuel", 410.0, 500.0)
    )

    val savingsGoals = listOf(
        SampleSavingsGoal("6-Month Emergency Fund", 18500.0, 24000.0, "Target: Jun 2027"),
        SampleSavingsGoal("Family Home Renovation", 12400.0, 20000.0, "Target: Dec 2027"),
        SampleSavingsGoal("Annual Family Vacation", 3200.0, 4500.0, "Target: Aug 2027")
    )

    val upcomingLoans = listOf(
        SampleLoanSummary("Family Hybrid Vehicle Loan", 14250.0, 420.0, "Due Nov 25"),
        SampleLoanSummary("Home Improvement Credit", 4200.0, 195.0, "Due Nov 28")
    )

    val recentActivity = listOf(
        SampleActivityItem("Bi-Weekly Household Salary", "Primary Earner", "+$3,850.00", true, "Income", "2h ago"),
        SampleActivityItem("Fresh Market Groceries", "Partner", "-$142.80", false, "Expense", "Yesterday"),
        SampleActivityItem("Index Fund Auto-Contribution", "Shared Vault", "+$950.00", true, "Investment", "2d ago"),
        SampleActivityItem("Electricity & Fiber Internet", "Household", "-$168.50", false, "Utilities", "3d ago")
    )
}
