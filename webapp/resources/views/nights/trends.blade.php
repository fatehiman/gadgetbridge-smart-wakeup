@extends('layouts.app')
@section('title', 'Trends')
@push('head')
    <script src="/vendor/chart.umd.js" defer></script>
    <script src="/assets/trends.js?v={{ @filemtime(public_path('assets/trends.js')) }}" defer></script>
@endpush
@section('content')
<div class="page-head">
    <h1 class="page-title"><svg class="ic"><use href="/assets/icons.svg#i-trending-up"/></svg> Trends</h1>
</div>
<form class="card row" method="get">
    <div><label for="period">Period</label>
        <select id="period" name="period">
            @foreach (['week' => 'Last 7 days', 'month' => 'Last 30 days', 'custom' => 'Custom range'] as $k => $l)
                <option value="{{ $k }}" @selected($period === $k)>{{ $l }}</option>
            @endforeach
        </select></div>
    <div><label for="from">From (custom)</label><input id="from" type="date" name="from" value="{{ $from }}"></div>
    <div><label for="to">To</label><input id="to" type="date" name="to" value="{{ $to }}"></div>
    <div class="auto"><button class="btn primary">Show</button></div>
</form>
<p class="muted">{{ $count }} night(s) from {{ $from }} to {{ $to }}.</p>
<div class="grid2">
    <div class="card"><h2>Wake time vs target</h2><div class="chart-box"><canvas id="t-wake"></canvas></div></div>
    <div class="card"><h2>Wake reasons</h2><div class="chart-box"><canvas id="t-reasons"></canvas></div></div>
    <div class="card"><h2>Average heart rate</h2><div class="chart-box"><canvas id="t-hr"></canvas></div></div>
    <div class="card"><h2>Sleep duration (hours)</h2><div class="chart-box"><canvas id="t-dur"></canvas></div></div>
</div>
<script type="application/json" id="trend-data">@json($data)</script>
@endsection
