/************************************
 * STATE SYNCHRONIZATION
 ************************************/
var oldViewState = {"version": 0};
var currentViewState = {"version": 0};
var nextViewState = {"version": 0};
var stateInterpolation = 1.0;

function poll() {
	var req = new XMLHttpRequest();
	req.open("get", "/listen/" + pageKey + "?p=" + currentViewState["version"], true);
	req.onerror = function (e) {
		console.error(req.statusText);
		setTimeout(poll, 3000);
	}
	req.onload = function (e) {
		if (req.readyState === 4) {
			var rsp = req.responseText;
			updateState(JSON.parse(rsp));
			poll();
		}
	}
	req.send(null);
}

function push(obj) {
	var req = new XMLHttpRequest();
	req.open("post", "/update/" + pageKey, true);
	req.send(JSON.stringify(obj));
}

function pushBgImage(img) {
	push({
			"type": "bgimage",
			"bgimage": img,
			});
}

function pushDeleteMarker(id) {
	push({
			"type": "mdel",
			"id": id
			});
}

function pushMarker(id) {
	var m = currentViewState["markers"][id];
	push({
			"type": "mpush",
			"id": id,
			"posx": m["posx"],
			"posy": m["posy"],
			"shape": m["shape"],
			"color": m["color"],
			"label": m["label"],
			"rotation": m["rotation"],
			"size": m["size"]
			});
}

function updateState(nu) {
	// If bgimage text equals the old state, update it to the new state (don't overwrite edits).
	var bgval = document.getElementById("bgimage").value;
	if (bgval == "" || bgval == currentViewState["bgimage"]) {
		document.getElementById("bgimage").value = nu["bgimage"];
	}
	if (currentViewState["bgimage"] != nu["bgimage"]) {
		// Start loading the new image to display.
		reloadBackground(nu["bgimage"]);
	}
	// Ignore changes to the currently-selected marker.
	if (selectedMarker != null) {
		nu["markers"][selectedMarker] = nextViewState["markers"][selectedMarker];
	}
	prevFrame = Date.now();
	oldViewState = currentViewState;
	stateInterpolation = 0;
	nextViewState = nu;
	onUpdateState();
	interpolate();
}

func onUpdateState() {
	// Do nothing. Other pages may override this function to hook updates.
}

var prevFrame;
function interpolate() {
	var t = Date.now();
	stateInterpolation += (t - prevFrame) / 500;
	if (stateInterpolation > 1) {
		stateInterpolation = 1;
	}
	prevFrame = t;
	var c = JSON.parse(JSON.stringify(nextViewState));
	var p = oldViewState["markers"];
	var n = nextViewState["markers"];
	if (n != null && p != null) {
		var m = c["markers"];
		for (var str in m) {
			if (m.hasOwnProperty(str)) {
				if (str == selectedMarker) {
					continue;
				}
				var old = p[str];
				if (old != null) {
					m[str]["posx"] = n[str]["posx"] * stateInterpolation + old["posx"] * (1 - stateInterpolation);
					m[str]["posy"] = n[str]["posy"] * stateInterpolation + old["posy"] * (1 - stateInterpolation);
					m[str]["rotation"] = n[str]["rotation"] * stateInterpolation + old["rotation"] * (1 - stateInterpolation);
					m[str]["size"] = n[str]["size"] * stateInterpolation + old["size"] * (1 - stateInterpolation);
				}
			}
		}
	}
	currentViewState = c;
	paintCanv();
	if (stateInterpolation < 1) {
		setTimeout(interpolate, 16);
	}
}

// Kick off a poll() to get the initial state.
poll();

/************************************
 * CANVAS PAINTING
 ************************************/

var bgimage = null;

function reloadBackground(url) {
	var image = new Image();
	image.onload = function() {
		bgimage = image;
		var canv = document.getElementById("canv");
		canv.width = image.width;
		canv.height = image.height;
		paintCanv();
	};
	image.src = url;
}

// Draws the entire canvas.
function paintCanv() {
	var canv = document.getElementById("canv");
	var ctx = canv.getContext("2d");
	var w = canv.width;
	var h = canv.height;
	ctx.clearRect(0, 0, w, h);
	if (bgimage == null) {
		ctx.fillStyle="#168820";
		ctx.fillRect(0, 0, w, h);
	} else {
		ctx.drawImage(bgimage, 0, 0);
	}
	for (var key in currentViewState["markers"]) {
		if (currentViewState["markers"].hasOwnProperty(key)) {
			paintMarker(ctx, currentViewState["markers"][key]);
		}
	}
}

function paintMarker(ctx, marker) {
	ctx.fillStyle = marker["color"]
	setMarkerPath(ctx, marker);
	ctx.fill();
	var rgb = fromHex(marker["color"]);
	if (rgb.r + rgb.g + rgb.b > 1.5) {
		ctx.strokeStyle = "#000000";
		ctx.fillStyle = "#000000";
	} else {
		ctx.strokeStyle = "#ffffff";
		ctx.fillStyle = "#ffffff";
	}
	ctx.stroke();
	var t = ctx.measureText(marker["label"]);
	ctx.fillText(marker["label"], marker["posx"] - t.width / 2, marker["posy"]);
}

function setMarkerPath(ctx, marker) {
	ctx.beginPath();
	var geo = geometry[marker["shape"]];
	var rot = marker["rotation"];
	var c = {x: marker["posx"], y: marker["posy"]};
	var s = marker["size"];
	var points = [];
	for (var i = 0; i < geo.length; i++) {
		var p = add(rotate(geo[i].x * s, geo[i].y * s, rot), c);
		ctx.lineTo(p.x, p.y);
	}
	ctx.closePath();
}

/************************************
 * MATH AND GEOMETRY UTILS
 ************************************/

function dot(a, b) {
	return a.x*b.x+a.y*b.y;
}

function cross(a, b) {
	return a.x*b.y-a.y*b.x;
}

function mag(a) {
	return Math.sqrt(dot(a,a));
}

function add(a, b) {
	return {x: a.x + b.x, y: a.y + b.y};
}

function rotate(x, y, d) {
	return {x: Math.cos(d) * x + Math.sin(d) * y, y: Math.cos(d) * y - Math.sin(d) * x};
}

var geometry = [
	[{x: 0.0, y: 1.0}, {x: 1.0, y: -1.0}, {x:  0.0, y: -0.5}, {x: -1.0, y: -1.0}], // Shape 0: Caret.
	[{x: 0.0, y: 1.0}, {x: 0.8, y:  0.8}, {x:  1.0, y: -1.0}, {x: -1.0, y: -1.0}, {x: -0.8, y: 0.8}], // Shape 1: Shogi.
	[{x: 1.0, y: 1.0}, {x: 1.0, y: -1.0}, {x: -1.0, y: -1.0}, {x: -1.0, y:  1.0}], // Shape 2: Square.
	[{x: 1.0, y: 1.0}, {x: 0.0, y:  0.5}, {x: -1.0, y:  1.0}, {x: -0.5, y:  0.0}, {x: -1.0, y: -1.0}, {x: 0.0, y: -0.5}, {x: 1.0, y: -1.0}, {x: 0.5, y: 0.0}], // Shape 2: "X".
	[{x: 1.0, y: 1.0}, rotate(1, 1, Math.PI/3), rotate(1, 1, 2*Math.PI/3), rotate(1, 1, Math.PI), rotate(1, 1, 4*Math.PI/3), rotate(1, 1, 5*Math.PI/3)] // Shape 3: Hexagon.
];

/************************************
 * INTERFACE
 ************************************/
var grabpt = {x: 0, y: 0}
var selectedMarkerInitialSize;
var selectedMarker = null;

function eventToLoc(e) {
	var canv = document.getElementById("canv");
	var rect = canv.getBoundingClientRect();
	return {x: e.clientX-rect.left, y: e.clientY-rect.top};
}

function mouseDown(e) {
	grabpt = eventToLoc(e);
	var ctx = document.getElementById("canv").getContext("2d");
	selectedMarker = null;
	for (var key in currentViewState["markers"]) {
		if (currentViewState["markers"].hasOwnProperty(key)) {
			var m = currentViewState["markers"][key];
			setMarkerPath(ctx, m);
			if (ctx.isPointInPath(grabpt.x, grabpt.y)) {
				selectedMarker = key;
				break;
			}
		}
	}
	if (getMode() == "CLONE" && selectedMarker != null) {
		var s = currentViewState["markers"][selectedMarker];
		var name = Math.random().toString(36);
		var m = {
			"posx": s["posx"],
			"posy": s["posy"],
			"rotation": s["rotation"],
			"color": s["color"],
			"label": s["label"],
			"size": s["size"],
			"shape": s["shape"]
		};
		currentViewState["markers"][name] = m;
		nextViewState["markers"][name] = m;
		selectedMarker = name;
	} else if (getMode() == "LABEL" && selectedMarker != null) {
		currentViewState["markers"][selectedMarker]["label"] = document.getElementById("label").value;
		nextViewState["markers"][selectedMarker] = JSON.parse(JSON.stringify(currentViewState["markers"][selectedMarker]));
	} else if (getMode() == "RESIZE" && selectedMarker != null) {
		selectedMarkerInitialSize = currentViewState["markers"][selectedMarker]["size"];
	} else if (getMode() == "DELETE" && selectedMarker != null) {
		pushDeleteMarker(selectedMarker);
		selectedMarker = null;
	} else if (selectedMarker == null) {
		var name = Math.random().toString(36);
		var m = {
			"posx": grabpt.x,
			"posy": grabpt.y,
			"rotation": 0.0,
			"color": "#" + toHexPad(Math.floor(Math.random()*0xffffff).toString(16), 6),
			"label": "",
			"size": 16,
			"shape": 0
		};
		currentViewState["markers"][name] = m;
		nextViewState["markers"][name] = m;
		selectedMarker = name;
	}
	paintCanv();
	e.preventDefault();
}

function mouseUp(e) {
	if (selectedMarker != null) {
		pushMarker(selectedMarker);
	}
	selectedMarker = null;
}

function mouseMotion(e) {
	e.preventDefault();
	if (selectedMarker != null) {
		var mode = getMode();
		newpoint = eventToLoc(e);
		var m = currentViewState["markers"][selectedMarker];
		var updateGrabPt = true;
		if (mode == "MOVE" || mode == "CLONE") {
			var x = m["posx"];
			var y = m["posy"];
			var pdelta = {x: x - grabpt.x, y: y - grabpt.y};
			var ndelta = {x: newpoint.x - x, y: newpoint.y - y};
			var dist = mag(pdelta);
			var ndeltamag = mag(ndelta);
			ndelta.x = ndelta.x / ndeltamag;
			ndelta.y = ndelta.y / ndeltamag;
			m["posx"] = -dist*ndelta.x + newpoint.x;
			m["posy"] = -dist*ndelta.y + newpoint.y;
			if (dist != 0) {
				m["rotation"] = m["rotation"] + Math.atan2(cross(pdelta,ndelta),-dot(pdelta,ndelta));
			}
		} else if (mode == "RESIZE") {
			var x = m["posx"];
			var y = m["posy"];
			var pdelta = {x: x - grabpt.x, y: y - grabpt.y};
			var ndelta = {x: x - newpoint.x, y: y - newpoint.y};
			var xscale = ndelta.x / pdelta.x;
			var yscale = ndelta.y / pdelta.y;
			var res = Math.min(256, Math.max(5.0, selectedMarkerInitialSize * (xscale + yscale) / 2));
			if (!isNaN(res)) {
				m["size"] = res;
			}
			updateGrabPt = false;
		} else if (mode == "HUE") {
			var hdelta = newpoint.x - grabpt.x;
			var vdelta = newpoint.y - grabpt.y;
			var c = toHSV(fromHex(m["color"]));
			c["h"] = (c["h"] + hdelta / 30 + 6) % 6;
			c["v"] = Math.min(1, Math.max(0, c["v"] - vdelta / 255));
			m["color"] = toHex(toRGB(c));
		} else if (mode == "SATURATION") {
			var sdelta = newpoint.x - grabpt.x;
			var vdelta = newpoint.y - grabpt.y;
			var c = toHSV(fromHex(m["color"]));
			c["s"] = Math.min(1, Math.max(0, c["s"] + sdelta / 255));
			c["v"] = Math.min(1, Math.max(0, c["v"] - vdelta / 255));
			m["color"] = toHex(toRGB(c));
		} else if (mode == "SHAPE") {
			var sdelta = (newpoint.x - grabpt.x) / 6;
			if (sdelta < 0) {
				sdelta = Math.ceil(sdelta);
			} else {
				sdelta = Math.floor(sdelta);
			}
			if (sdelta == 0) {
				updateGrabPt = false;
			}
			m["shape"] = (m["shape"] + sdelta + geometry.length) % geometry.length;
		}
		nextViewState["markers"][selectedMarker] = JSON.parse(JSON.stringify(m));
		if (updateGrabPt) {
			grabpt = newpoint;
		}
		paintCanv();
	}
}

function getMode() {
	var buttons = document.getElementsByName("mode");
	for (var i = 0; i < buttons.length; i++) {
		if (buttons[i].checked) {
			return buttons[i].value;
		}
	}
	return "MOVE";
}

function toHSV(rgbcolor) {
	var r = rgbcolor["r"];
	var g = rgbcolor["g"];
	var b = rgbcolor["b"];
	var max = Math.max(r, Math.max(g, b));
	var min = Math.min(r, Math.min(g, b));
	var v = max;
	var s = 0;
	var h = 0;
	var delta = max - min;
	if (max != 0) {  // Not black.
		s = delta / max;
		if (delta != 0) {
			if (r == max) {
				h = (g - b) / delta;
			} else if (g == max) {
				h = 2 + (b - r) / delta;
			} else if (b == max) {
				h = 4 + (r - g) / delta;
			}
			if (h < 0) {
				h += 6;
			}
		}
	}
	return {
		"h": h,
		"s": s,
		"v": v
	};
}

function toRGB(hsvcolor) {
	var c = hsvcolor["s"] * hsvcolor["v"];
	var m = hsvcolor["v"]	- c;
	var h = hsvcolor["h"];
	var x = c * (1 - Math.abs(h % 2 - 1)) + m;
	var c = c + m;
	if (h < 1)      return {"r": c, "g": x, "b": m};
	else if (h < 2) return {"r": x, "g": c, "b": m};
	else if (h < 3) return {"r": m, "g": c, "b": x};
	else if (h < 4) return {"r": m, "g": x, "b": c};
	else if (h < 5) return {"r": x, "g": m, "b": c};
	else            return {"r": c, "g": m, "b": x};
}

function toHex(rgbcolor) {
	return "#"
			+ toHexPad(Math.floor(rgbcolor["r"]*255).toString(16), 2)
			+ toHexPad(Math.floor(rgbcolor["g"]*255).toString(16), 2)
			+ toHexPad(Math.floor(rgbcolor["b"]*255).toString(16), 2);
}

function toHexPad(str, l) {
		while (str.length < l) {
			str = "0" + str;
		}
		return str;
}

function fromHex(hexcolor) {
	return {
		"r": parseInt(hexcolor.substring(1, 3), 16) / 255.0,
		"g": parseInt(hexcolor.substring(3, 5), 16) / 255.0,
		"b": parseInt(hexcolor.substring(5, 7), 16) / 255.0
	};
}
