// WebCall Copyright 2022 timur.mobi. All rights reserved.
'use strict';

const container = document.querySelector('div#container');
const formDomain = document.querySelector('input#domain');
const formUsername = document.querySelector('input#username');
const numericIdCheckbox = document.querySelector('input#numericId');
const divspinnerframe = document.querySelector('div#spinnerframe');
const divspinner = document.querySelector('div#spinner');
const clearCookies = document.querySelector('input#clearCookies');
const clearCache = document.querySelector('input#clearCache');
const insecureTls = document.querySelector('input#insecureTls');
const insecureTlsLabel = document.querySelector('label#insecureTlsLabel');

var domain = "";
var username = "";
var versionName = "";

window.onload = function() {
	domain = Android.readPreference("webcalldomain").toLowerCase();
	username = Android.readPreference("username").toLowerCase();
	if(username=="register") {
		username="";
		Android.storePreference("username", "");
	}

	versionName = Android.getVersionName();
	var lastUsedVersionName = Android.readPreference("versionName");
	console.log("versionName="+versionName+" last="+lastUsedVersionName);

	if(lastUsedVersionName!=versionName) {
		if(clearCache)
			clearCache.checked = true;
		// store the new versionName, so that the speech bubbles do not appear next time
		console.log("store versionName "+versionName);
		Android.storePreference("versionName", versionName);
		//Android.storePreferenceLong("keepAwakeWakeLockMS", 0);
	}

	document.getElementById("webcallversion").innerHTML = Android.getVersionName();

	var webviewVersion = Android.webviewVersion();
	if(!webviewVersion) {
		webviewVersion = "no version, old webview?";
	} else if(webviewVersion<"80.0") {
		webviewVersion = webviewVersion + " TOO OLD!";
	}
	document.getElementById("webviewversion").innerHTML = webviewVersion;

	if(domain=="") {
		domain = "timur.mobi";
	} else if(domain==" ") {
		domain = "";
	}
	console.log("domain "+domain);
	console.log("username "+username);
	formDomain.value = domain;
	formUsername.value = username;

	insecureTls.addEventListener('change', function() {
		insecureTlsAction();
	});

	domainAction();

	if(formUsername.value=="" || !isNaN(formUsername.value)) {
		// username is a number
		console.log("formUsername.value ("+formUsername.value+") is numeric");
		formUsername.setAttribute('type','number');
		numericIdCheckbox.checked = true;
	} else {
		console.log("formUsername.value ("+formUsername.value+") is NOT numeric");
		numericIdCheckbox.checked = false;
	}
	numericIdCheckbox.addEventListener('change', function() {
		if(this.checked) {
			console.log("numericIdCheckbox checked");
			if(formUsername.value!="" && isNaN(formUsername.value)) {
				formUsername.value = "";
			}
			formUsername.setAttribute('type','number');
		} else {
			console.log("numericIdCheckbox unchecked");
			formUsername.setAttribute('type','text');
		}
		formUsername.focus();
	});

	// remove focus from any of the elements (to prevent accidental modification)
	setTimeout(function() {
		document.activeElement.blur();
	},400);

	// will proceed in connectServer() or requestNewId()
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

function domainAction() {
	// call insecureTlsAction() if valueDomain (without :port) is an ip-address
	let valueDomain = formDomain.value;
	let valueDomainWithoutPort = valueDomain;
	let portIdx = valueDomainWithoutPort.indexOf(":");
	if(portIdx>=0) {
		valueDomainWithoutPort = valueDomainWithoutPort.substring(0,portIdx);
	}
	// https://stackoverflow.com/questions/4460586/javascript-regular-expression-to-check-for-ip-addresses
	if(valueDomainWithoutPort.split(".").map(ip => Number(ip) >= 0 && Number(ip) <= 255).includes(false)) {
		// not a valid ip-address
		console.log("domainAction: not an ip-address: "+valueDomainWithoutPort);
		insecureTls.checked = false;
	} else {
		// a valid ip-address
		console.log("domainAction: is a valid ip-address: "+valueDomainWithoutPort);
		insecureTls.checked = true;
	}
	insecureTlsAction();
}

function insecureTlsAction() {
	if(insecureTls.checked) {
		console.log("insecureTls checked");
		insecureTlsLabel.style.color = "#f44";
		Android.insecureTls(true);
		Android.wsClearCache(false,false);
	} else {
		console.log("insecureTls unchecked");
		insecureTlsLabel.style.color = "";
		Android.insecureTls(false);
		Android.wsClearCache(false,false);
	}
}

function requestNewId() {
	if(formUsername.value!="") {
		Android.toast("To register a new User-ID, clear the current User-ID first.");
		setTimeout(function() { document.activeElement.blur(); },100); // deactivate button
		return;
	}

	Android.storePreference("username", "");
	Android.wsClearCookies();
	// using randId for better ssl-err detection
	let randId = ""+Math.floor(Math.random()*1000000);
	let url = "https://"+formDomain.value+"/callee/register/?i="+randId;
	console.log('load register page='+url);
	setTimeout(function() { document.activeElement.blur(); },100); // deactivate button
	// TODO when url fails, due to an ssl-err, we do NOT get an error in JS (only in Java: # onReceivedSslError)
	window.location.href = url;
}

function connectServer() {
	var valueDomain = formDomain.value.toLowerCase();
	console.log('valueDomain',valueDomain);
	var valueUsername = formUsername.value.toLowerCase();
	console.log('valueUsername',valueUsername);

	if(valueDomain!="") {
		Android.storePreference("webcalldomain", valueDomain);
	}

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
		Android.wsClearCache(false,false);
	} else {
		// Android.wsClearCache() also after 2 days
		var lastClearCache = Android.readPreferenceLong("lastClearCache");
		console.log("lastClearCache="+lastClearCache);
		if(lastClearCache>0) {
			var nowTime = new Date().getTime();
			var diffSecs = (nowTime - lastClearCache)/1000;
			console.log("nowTime="+nowTime+" diffSecs="+diffSecs);
			if(diffSecs > 2*24*3600) {
				console.log("time triggered wsClearCache");
				Android.wsClearCache(false,false);
			}
		}
	}

/*
	if(insecureTls.checked) {
		console.log('insecureTls true');
		Android.insecureTls(true);
	} else {
		console.log('insecureTls false');
		Android.insecureTls(false);
	}
*/

	if(valueUsername=="") {
		// register new ID
		console.log('username is empty');
		Android.toast("Cannot start with an empty User-ID.");
		setTimeout(function() { document.activeElement.blur(); },100); // deactivate button
		return;
	}

	let isNetwork = Android.isNetwork();
	console.log('isNetwork='+isNetwork);
	if(!isNetwork) {
		// there is no point advancing if we have no network
		setTimeout(function() { document.activeElement.blur(); },100); // deactivate button
		alert("no network");
		return;
	}

	var abort = false;
	if(divspinnerframe) {
		// show divspinnerframe only if loading of main page is delayed
		setTimeout(function() {
			if(!abort) {
				divspinnerframe.style.display = "block";
			}
		},200);
	}

	//let randId = ""+Math.floor(Math.random()*1000000); // ajaxFetch() does this for us
	let api = "https://"+valueDomain+"/rtcsig/online?id="+valueUsername+
		"&ver="+Android.getVersionName()+"_"+Android.webviewVersion(); //+"&i="+randId;
	console.log('webcall.js check online '+api);
	// NOTE: we need "notavail" to continue
	ajaxFetch(new XMLHttpRequest(), "GET", api, function(xhr) {
		console.log('xhr response ('+xhr.responseText+')');
		if(xhr.responseText.startsWith("error")) {
			console.log('xhr response '+xhr.responseText);
		} else if(xhr.responseText.startsWith("busy")) {
			console.log('xhr response '+xhr.responseText);
		} else if(xhr.responseText.startsWith("notavail")) {
			// user is not online! this is what we want
			setTimeout(function() {
				// if we are still here after 8s, window.location.replace has failed
				// maybe an ssl issue
				console.log('window.location.replace has failed');
				abort = true;
				divspinnerframe.style.display = "none";
				document.activeElement.blur();
			},8000);
			// switch to callee page
			let url = "https://"+valueDomain+"/callee/"+valueUsername+"?auto=1";
			console.log('load main '+url);
			window.location.replace(url);
			return;
		} else if(xhr.responseText.startsWith("clear")) {
			formDomain.value = "";
			Android.storePreference("webcalldomain", " ");
		} else if(xhr.responseText.startsWith("wss://") || xhr.responseText.startsWith("ws://")) {
			// a callee is already logged in
			abort = true;
			divspinnerframe.style.display = "none";
			document.activeElement.blur();
			Android.toast("Busy. Already logged in from another device?");
			return;
		}
		abort = true;
		divspinnerframe.style.display = "none";
		setTimeout(function() { document.activeElement.blur(); },100); // deactivate button
		Android.toast("Connection failed. Please check server address and user ID.");
	}, function(errString,errcode) {
		console.log('xhr error ('+errString+') errcode='+errcode);
		abort = true;
		divspinnerframe.style.display = "none";
		setTimeout(function() { document.activeElement.blur(); },100); // deactivate button
		Android.toast("Connection failed. Please check your server address and user ID.");
	});
}

var xhrTimeout = 25000;
function ajaxFetch(xhr, type, api, processData, errorFkt, postData) {
	xhr.onreadystatechange = function() {
		if(xhr.readyState == 4 && (xhr.status==200 || xhr.status==0)) {
			processData(xhr);
		} else if(xhr.readyState==4) {
			errorFkt("fetch error",xhr.status);
		}
	}
	xhr.timeout = xhrTimeout;
	xhr.ontimeout = function() {
		errorFkt("timeout",0);
	}
	xhr.onerror= function(e) {
		console.log('xhr.onerror '+e.type+' '+e.loaded);
		errorFkt("fetching",xhr.status);
	};
	// cross-browser compatible approach to bypassing the cache
	if(api.indexOf("?")>=0) {
		api += "&_="+new Date().getTime();
	} else {
		api += "?_="+new Date().getTime();
	}
	console.log('webcall.js xhr '+api);
	xhr.open(type, api, true);
	xhr.setRequestHeader("Content-type", "text/plain; charset=utf-8");
	if(postData) {
		xhr.send(postData);
	} else {
		xhr.send();
	}
}

