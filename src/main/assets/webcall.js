// WebCall Copyright 2021 timur.mobi. All rights reserved.
'use strict';

const container = document.querySelector('div#container');
const formDomain = document.querySelector('input#domain');
const formUsername = document.querySelector('input#username');
const divspinnerframe = document.querySelector('div#spinnerframe');
const divspinner = document.querySelector('div#spinner');
const clearCookies = document.querySelector('input#clearCookies');
const clearCache = document.querySelector('input#clearCache');
//const ringOnSpeaker = document.querySelector('input#ringOnSpeaker');
//const nfcConnect = document.querySelector('input#nfcConnect');
//const startOnBoot = document.querySelector('input#startOnBoot');
const submitButton = document.querySelector('input#submit');
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

//TODO remote these lines when functionality is implemented
//	nfcConnect.disabled = true;
//	nfcConnectLabel.style.opacity = 0.6;
//	startOnBoot.disabled = true;
//	startOnBootLabel.style.opacity = 0.6;

	if(typeof Android !== "undefined" && Android !== null) {
/*
		if(Android.androidApiVersion() >= 28) {
			// TODO: for now we don't support ringOnSpeaker on Android 9+
			ringOnSpeaker.disabled = true;
			ringOnSpeakerLabel.style.opacity = 0.6;
		} else {
			ringOnSpeaker.checked = Android.readPreferenceBool("ringOnSpeaker");
		}
*/
//TODO enable these lines when functionality is implemented
//		nfcConnect.checked = Android.readPreferenceBool("nfcConnect");
//		startOnBoot.checked = Android.readPreferenceBool("startOnBoot");
	}

	setTimeout(function() {
		submitButton.focus();
	},800);
	// see: submitFormDone() below
}

/*
function getUrlParams(param) {
	if(window.location.search!="") {
		console.log("getUrlParams search="+window.location.search);
		var query = window.location.search.substring(1);
		var parts = query.split("&");
		for (var i=0;i<parts.length;i++) {
			//gLog("getUrlParams part(%d)=%s",i,parts[i]);
			var seg = parts[i].split("=");
			if (seg[0] == param) {
				//gLog("getUrlParams found=(%s)",seg[1]);
				if(typeof seg[1]!=="undefined" && seg[1]!="" && seg[1]!="undefined") {
					return decodeURI(seg[1]);
				}
				return true;
			}
		}
	}
	return "";
}
*/

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
//		if(valueDomain!=domain) {
			Android.storePreference("webcalldomain", valueDomain);
//		}
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

//		console.log('store ringOnSpeaker '+ringOnSpeaker.checked);
//		Android.storePreferenceBool("ringOnSpeaker", ringOnSpeaker.checked);

//		console.log('store nfcConnect '+nfcConnect.checked);
//		Android.storePreferenceBool("nfcConnect", nfcConnect.checked);

//		console.log('store startOnBoot '+startOnBoot.checked);
//		Android.storePreferenceBool("startOnBoot", startOnBoot.checked);
	}

	if(valueUsername=="") {
		if(confirm('Do you want to register a new WebCall user ID?')) {
			Android.storePreference("username", "");
			Android.wsClearCookies();

			let url = "https://"+valueDomain+"/callee/register";
			console.log('load register page'+url);
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


