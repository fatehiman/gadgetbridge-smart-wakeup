@extends('layouts.app')
@section('title', 'Nights')
@section('content')
@php use App\Support\Fmt; @endphp
<div class="page-head">
    <h1 class="page-title"><svg class="ic"><use href="/assets/icons.svg#i-moon"/></svg> Nights</h1>
</div>
<form class="card row" method="get">
    <div><label for="from">From</label><input id="from" type="date" name="from" value="{{ request('from') }}"></div>
    <div><label for="to">To</label><input id="to" type="date" name="to" value="{{ request('to') }}"></div>
    <div class="auto"><button class="btn primary">Filter</button></div>
    @if (request()->hasAny(['from', 'to']))<div class="auto"><a class="btn" href="{{ route('nights') }}">Clear</a></div>@endif
</form>
<div class="card table-wrap">
    <table>
        <thead><tr><th>Date</th><th>Mode</th><th>Target</th><th>Woke at</th><th>Reason</th><th>Duration</th><th>Avg / min HR</th><th>Rating</th></tr></thead>
        <tbody>
        @forelse ($rows as $row)
            @php $s = $row->s; $z = $s->timezone ?: $tz; @endphp
            <tr>
                <td><a href="{{ route('nights.show', $s->id) }}">{{ Fmt::t($s->started_at, $z, 'D, M j Y') }}</a><div class="small muted">{{ Fmt::t($s->started_at, $z, 'H:i') }}</div></td>
                <td><span class="pill c{{ $s->mode === 'smart_wake' ? 3 : 0 }}">{{ str_replace('_', ' ', $s->mode ?? '–') }}</span></td>
                <td>{{ Fmt::t($s->target_time, $z, 'H:i') }}@if ($s->tolerance_min !== null)<span class="muted small"> ±{{ $s->tolerance_min }}</span>@endif</td>
                <td>{{ Fmt::t($row->wake_ts, $z, 'H:i') }}</td>
                <td>@if ($row->wake_reason)<span class="pill c{{ in_array($row->wake_reason, ['LIGHT_SLEEP']) ? 2 : (in_array($row->wake_reason, ['DEADLINE', 'FALLBACK']) ? 1 : 4) }}">{{ Fmt::reason($row->wake_reason) }}</span>@else – @endif</td>
                <td>{{ Fmt::dur($row->duration) }}</td>
                <td>{{ $row->avg_hr ?? '–' }} / {{ $row->min_hr ?? '–' }}</td>
                <td>{{ $s->morning_rating ? str_repeat('★', $s->morning_rating) : '–' }}</td>
            </tr>
        @empty
            <tr><td colspan="8" class="muted">No nights yet.</td></tr>
        @endforelse
        </tbody>
    </table>
</div>
@if ($page->hasPages())
    <div class="pager">
        @if (! $page->onFirstPage())<a class="btn" href="{{ $page->previousPageUrl() }}">← Newer</a>@endif
        @if ($page->hasMorePages())<a class="btn" href="{{ $page->nextPageUrl() }}">Older →</a>@endif
    </div>
@endif
@endsection
