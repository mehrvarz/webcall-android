// WebCall Copyright 2022 timur.mobi. All rights reserved.
'use strict';

const container = document.querySelector('div#container');
const formDomain = document.querySelector('input#domain');
const formUsername = document.querySelector('input#username');
const divspinnerframe = document.querySelector('div#spinnerframe');
const divspinner = document.querySelector('div#spinner');
const clearCookies = document.querySelector('input#clearCookies');
const clearCache = document.querySelector('input#clearCache');
const insecureTls = document.querySelector('input#insecureTls');
const insecureTlsLabel = document.querySelector('label#insecureTlsLabel');
const submitButton = document.querySelector('input#submit');

var domain = "";
var username = "";
var versionName = "";

window.onload = function() {
	if(typeof Android !== "undefined" && Android !== null) {
		domain = Android.readPreference("webcalldomain");
		username = Android.readPreference("username");
		if(username=="register") {
			username="";
			Android.storePreference("username", "");
		}

		versionName = Android.getVersionName();
		var lastUsedVersionName = Android.readPreference("versionName");
		console.log("versionName "+versionName);
		if(lastUsedVersionName=="") {
			// the user has upgraded (or downgraded) the webcall apk
			console.log("upgrade from lastUsedVersionName "+lastUsedVersionName);
			var bubbleElement;
/*
			setTimeout(function() {
			bubbleElement = document.createElement("div");
			bubbleElement.classList.add("speechbubble");
			bubbleElement.id = "speechBubble";
			// TODO it would of course be much better if we could aligh the bubbles with 
			// the elements they refer to
			bubbleElement.style = "left:5%;top:155px;max-width:75%;width:320px;padding:20px;";
			bubbleElement.innerHTML = "To use your own WebCall server, enter your domain name here.";
			bubbleElement.onclick = function () {
				this.parentElement.removeChild(this);

				setTimeout(function() {
				bubbleElement = document.createElement("div");
				bubbleElement.classList.add("speechbubble");
				bubbleElement.id = "speechBubble";
				bubbleElement.style = "left:5%;top:230px;max-width:80%;width:320px;padding:20px;";
				bubbleElement.innerHTML = "To create a new phone number use a blank ID and click OK.";
				bubbleElement.onclick = function () {
					this.parentElement.removeChild(this);
*/
					setTimeout(function() {
					bubbleElement = document.createElement("div");
					bubbleElement.classList.add("speechbubble2");
					bubbleElement.id = "speechBubble";
					bubbleElement.style = "left:4%;top:300px;max-width:75%;width:320px;padding:20px;";
					bubbleElement.innerHTML = "Clear password cookie: force password form";
					bubbleElement.onclick = function () {
						this.parentElement.removeChild(this);

						setTimeout(function() {
						bubbleElement = document.createElement("div");
						bubbleElement.classList.add("speechbubble2");
						bubbleElement.id = "speechBubble";
						bubbleElement.style = "left:3%;top:350px;max-width:75%;width:320px;padding:20px;";
						bubbleElement.innerHTML = "Clear cache: reload WebCall core";
						bubbleElement.onclick = function () {
							this.parentElement.removeChild(this);

							setTimeout(function() {
							bubbleElement = document.createElement("div");
							bubbleElement.classList.add("speechbubble2");
							bubbleElement.id = "speechBubble";
							bubbleElement.style = "left:3%;top:390px;max-width:75%;width:320px;padding:20px;";
							bubbleElement.innerHTML = "Allow insecure TLS: skip certificate authentication";
							bubbleElement.onclick = function () {
								this.parentElement.removeChild(this);
							}
							container.appendChild(bubbleElement);
							},300);
						}
						container.appendChild(bubbleElement);
						},300);
					}
					container.appendChild(bubbleElement);
					},300);
/*
				}
				container.appendChild(bubbleElement);
				},300);
			}
			container.appendChild(bubbleElement);
			},300);
*/
		}
		if(lastUsedVersionName!=versionName) {
			if(clearCache)
				clearCache.checked = true;
			// store the new versionName, so that the speech bubbles do not appear next time
			console.log("store versionName "+versionName);
			Android.storePreference("versionName", versionName);
			//Android.storePreferenceLong("keepAwakeWakeLockMS", 0);
		}
	}
	if(domain=="") {
		domain = "timur.mobi";
	}
	console.log("domain "+domain);
	console.log("username "+username);
	formDomain.value = domain;
	formUsername.value = username;

	insecureTls.addEventListener('change', function() {
		if(this.checked) {
			console.log("insecureTls checked");
			insecureTlsLabel.style.color = "#f44";
		} else {
			console.log("insecureTls unchecked");
			insecureTlsLabel.style.color = "";
		}
	});

	// remove focus from any of the elements (to prevent accidental modification)
	document.activeElement.blur();
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
		if(insecureTls.checked) {
			console.log('insecureTls true');
			Android.insecureTls(true);
		} else {
			console.log('insecureTls false');
			Android.insecureTls(false);
		}
	}

	if(valueUsername=="") {
		Android.storePreference("username", "");
		Android.wsClearCookies();

		let url = "https://"+valueDomain+"/callee/register";
		console.log('load register page='+url);
		window.location.href = url;
		return;
	}

	// there is no point advancing if we have no network
	if(typeof Android !== "undefined" && Android !== null) {
		let isNetwork = Android.isNetwork();
		console.log('isNetwork='+isNetwork);
		if(!isNetwork) {
			document.activeElement.blur();
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
	setTimeout(function() {
		// if we are still here after 8s, window.location.replace has failed
		// maybe an ssl issue
		console.log('window.location.replace has failed');
		divspinnerframe.style.display = "none";
		document.activeElement.blur();
	},8000);

	let url = "https://"+valueDomain+"/callee/"+valueUsername+"?auto=1";
	console.log('load main '+url);
	window.location.replace(url);
}

