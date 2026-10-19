@extends('layouts.app')
@section('title', 'Log in')
@section('content')
<div class="login-wrap card">
    <div class="brand-logo"><svg class="ic" style="width:32px;height:32px"><use href="/assets/icons.svg#i-moon"/></svg></div>
    <h1 style="text-align:center">Gadget</h1>
    <form method="post" action="{{ route('login') }}">
        @csrf
        <p><label for="email">Email</label><input id="email" name="email" type="email" value="{{ old('email') }}" required autofocus>
            @error('email')<div class="errors">{{ $message }}</div>@enderror</p>
        <p><label for="password">Password</label><input id="password" name="password" type="password" required></p>
        <button class="btn primary" style="width:100%">Log in</button>
    </form>
    @if ($canRegister)
        <p class="small muted" style="text-align:center">No owner yet. <a href="{{ route('register') }}">Create the owner account</a></p>
    @endif
</div>
@endsection
