package com.mirunubi.bjstock.feature.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mirunubi.bjstock.core.kis.market.CurrentStockQuote
import com.mirunubi.bjstock.core.kis.market.DailyStockBar
import com.mirunubi.bjstock.core.kis.market.KisMarketNumeric

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketDataTestScreen(
    onBack: () -> Unit,
    viewModel: MarketDataTestViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Market Data Test") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Read-only KIS quotations. No Room persistence.")
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.symbol,
                    onValueChange = viewModel::onSymbolChanged,
                    label = { Text("종목코드") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = viewModel::inquireCurrentPrice,
                    enabled = !state.loadingQuote,
                ) { Text(if (state.loadingQuote) "조회 중..." else "현재가 조회") }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.startDate,
                    onValueChange = viewModel::onStartDateChanged,
                    label = { Text("시작일 (YYYY-MM-DD)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.endDate,
                    onValueChange = viewModel::onEndDateChanged,
                    label = { Text("종료일 (YYYY-MM-DD)") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = viewModel::inquireDailyBars,
                    enabled = !state.loadingBars,
                ) { Text(if (state.loadingBars) "조회 중..." else "일봉 조회") }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.quote?.let { QuoteCard(it) }
            }
            items(state.bars, key = { "${it.symbol}-${it.tradeDate}" }) { bar ->
                DailyBarCard(bar)
            }
        }
    }
}

@Composable
private fun QuoteCard(quote: CurrentStockQuote) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("현재가", style = MaterialTheme.typography.titleSmall)
            Text("Symbol ${quote.symbol}")
            Text("Current Price ${KisMarketNumeric.formatWon(quote.currentPrice)}")
            Text("Open ${KisMarketNumeric.formatWon(quote.openPrice)}")
            Text("High ${KisMarketNumeric.formatWon(quote.highPrice)}")
            Text("Low ${KisMarketNumeric.formatWon(quote.lowPrice)}")
            Text("Volume ${KisMarketNumeric.formatWon(quote.volume)}")
            Text("Change Rate ${KisMarketNumeric.formatPercentFromScaledRatio(quote.changeRate)}")
        }
    }
}

@Composable
private fun DailyBarCard(bar: DailyStockBar) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(KisMarketNumeric.formatDisplayDate(bar.tradeDate), style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text("O ${KisMarketNumeric.formatWon(bar.openPrice)}")
            Text("H ${KisMarketNumeric.formatWon(bar.highPrice)}")
            Text("L ${KisMarketNumeric.formatWon(bar.lowPrice)}")
            Text("C ${KisMarketNumeric.formatWon(bar.closePrice)}")
            Text("V ${KisMarketNumeric.formatWon(bar.volume)}")
        }
    }
}
