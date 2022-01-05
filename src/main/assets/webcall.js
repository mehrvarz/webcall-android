// WebCall Copyright 2021 timur.mobi. All rights reserved.
'use strict';

const container = document.querySelector('div#container');
const formDomain = document.querySelector('input#domain');
const formUsername = document.querySelector('input#username');
const divspinnerframe = document.querySelector('div#spinnerframe');
const divspinner = document.querySelector('div#spinner');
const clearCookies = document.querySelector('input#clearCookies');
const clearCache = document.querySelector('input#clearCache');
const submitButton = document.querySelector('input#submit');
//const howtoElement = document.querySelector('a#howto');
//const latestNewsElement = document.querySelector('a#latestNews');

var domain = "";
var username = "";

window.onload = function() {
	if(typeof Android !== "undefined" && Android !== null) {
		domain = Android.readPreference("webcalldomain");
		username = Android.readPreference("username");
		if(username=="register") {
			username="";
			Android.storePreference("username", "");
		}
	}
	if(domain=="") {
		domain = "timur.mobi";
	}
	console.log("domain "+domain);
	console.log("username "+username);
	formDomain.value = domain;
	formUsername.value = username;

	// remove focus from any of the elements (to prevent accidental change)
	document.activeElement.blur();
/*
	var howtoHref = "https://timur.mobi/webcall/android/";
	howtoHref += "?_="+new Date().getTime();
	howtoElement.href = howtoHref;
	//console.log('howtoElement.href='+howtoElement.href);

// TODO only make this link visible, if the user has not seen this content yet
	var latestNewsHref = "https://timur.mobi/webcall/android-news/";
	latestNewsHref += "?_="+new Date().getTime();
	latestNewsElement.href = latestNewsHref;
	//console.log('latestNewsElement.href='+latestNewsElement.href);
	latestNewsElement.style.display = "inline-block";
*/
	// will proceed in submitFormDone()
}

function clearForm(idx) {
	if(idx==0) {
		formDomain.value = "";
		formDomain.focus();
	} else if(idx==1) {
		formUsername.value = "";
		formUsername.focus();
	}
}

function submitFormDone(theForm) {
	var valueDomain = formDomain.value;
	console.log('valueDomain',valueDomain);
	var valueUsername = formUsername.value;
	console.log('valueUsername',valueUsername);
	// store valueDomain
	if(typeof Android !== "undefined" && Android !== null) {
		Android.storePreference("webcalldomain", valueDomain);

		if(valueUsername!="") {
			console.log('store valueUsername',valueUsername);
			Android.storePreference("username", valueUsername);
			if(valueUsername!=username) {
				// clear password cookie
				console.log('wsClearCookies (username changed)');
				Android.wsClearCookies();
			}
		}

		if(clearCookies.checked) {
			console.log('wsClearCookies (checkbox)');
			Android.wsClearCookies();
		}
		if(clearCache.checked) {
			console.log('wsClearCache');
			Android.wsClearCache();
		}
	}

	if(valueUsername=="") {
		if(confirm("Do you want to register a new WebCall user ID?\n\n"+
			"If so, please enter a password on the next page and click the generated link to continue.")) {
			Android.storePreference("username", "");
			Android.wsClearCookies();

			let url = "https://"+valueDomain+"/callee/register";
			console.log('load register page='+url);
			window.location.replace(url);
		}
		return;
	}

	if(typeof Android !== "undefined" && Android !== null) {
		// there is no point continuing if there is no network
		if(!Android.isNetwork()) {
			alert("no network");
			return;
		}
	}

	if(divspinnerframe) {
		// show divspinnerframe only if loading of main page is delayed
		setTimeout(function() {
			divspinnerframe.style.display = "block";
		},200);
	}
	let url = "https://"+valueDomain+"/callee/"+valueUsername+"?auto=1";
	console.log('load main '+url);
	window.location.replace(url);
}

