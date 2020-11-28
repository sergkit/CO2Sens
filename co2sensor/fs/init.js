load('api_config.js');
load('api_net.js');
load('api_rpc.js');
load('api_events.js');
load('api_sys.js');
load('api_mqtt.js');

let topic = '/devices/' + Cfg.get('device.id') + '/'; //add events | config | state for real topic


// RPC methods
RPC.addHandler('setwifi', function (args) {
    if (typeof (args) === 'object'
        && typeof (args.config) === 'object'
        && typeof (args.key) === "string"
        && args.key === Cfg.get('device.id')) {
        print('Request:', JSON.stringify(args.config));
        print('Rsave:', Cfg.set(args.config, true));
        Sys.reboot(500);
        return "System rebooting";
    } else {
        return { error: -1, message: Cfg.get('device.id') };
    }
});



// Monitor network connectivity.
Event.addGroupHandler(Net.EVENT_GRP, function (ev, evdata, arg) {
    let evs = '???';
    if (ev === Net.STATUS_DISCONNECTED ) {
        evs = 'DISCONNECTED';
    } else if (ev === Net.STATUS_CONNECTING) {
        evs = 'CONNECTING';
    } else if (ev === Net.STATUS_CONNECTED) {
        evs = 'CONNECTED';
    } else if (ev === Net.STATUS_GOT_IP) {
        evs = 'GOT_IP';
    }
    print('== Net event:', ev, evs);
}, null);

/**
 * subscribe to config and update sensors setting
 */
/*
MQTT.sub(topic + "config", function (conn, top, msg) {
    print('Topic ', top, 'Got config update:', msg.slice(0, 100));
    if (msg) {
        let obj = JSON.parse(msg);
        if (obj) {
            if (typeof obj.co2 === "number") {
                Cfg.set({ app: { co2_level: obj.co2 } });
                print("set config co2", Cfg.get('app.co2_level'));
            }

        }
    }
}, null);
*/