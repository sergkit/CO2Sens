const functions = require('firebase-functions');
const admin = require('firebase-admin');
const { OAuth2Client } = require('google-auth-library');
const { google } = require('googleapis');

const { ASAP } = require('downsample');

admin.initializeApp();
const db = admin.database();

// константы  для программы
const FUNCTIONS_REDIRECT = `https://us-central1-${process.env.GCLOUD_PROJECT}.cloudfunctions.net/oauthcallback`;
const CONFIG_CLIENT_ID = functions.config().googleapi.client_id;
const CONFIG_CLIENT_SECRET = functions.config().googleapi.client_secret;
// путь для сохранения токена
const DB_TOKEN_PATH = '/api_tokens';

/* для инициализации пременных окружения выполнить из командной строки
firebase functions:config:set googleapi.client_id="CLIENT_ID" googleapi.client_secret="SECRET" googleapi.mailto="MAILTO" googleapi.email="MAILFROM"
а потом 
firebase deploy --only functions
cd  D:\progs\CO2sens\firebase\functions
*/

// перечень используемых API, если изменить, нужно заново вызать функцию авторизации
const SCOPES = ['https://www.googleapis.com/auth/gmail.readonly', 'https://www.googleapis.com/auth/gmail.modify',
  'https://www.googleapis.com/auth/gmail.compose', 
  'https://www.googleapis.com/auth/gmail.send', 
  'https://www.googleapis.com/auth/cloudiot',
'https://www.googleapis.com/auth/cloud-platform'];

const functionsOauthClient = new OAuth2Client(CONFIG_CLIENT_ID, CONFIG_CLIENT_SECRET,
  FUNCTIONS_REDIRECT);

//вызов функции авторизации
exports.authgoogleapi = functions.https.onRequest((req, res) => {
  res.set('Cache-Control', 'private, max-age=0, s-maxage=0');
  res.redirect(functionsOauthClient.generateAuthUrl({
    access_type: 'offline',
    scope: SCOPES,
    prompt: 'consent',
  }));
});

// очистка статистики
exports.removestat = functions.https.onRequest(async (req, res) => {
  await db.ref("devices-telemetry/WMETER_11A214/stat").remove();
  await db.ref(`reg`).remove();
  return res.status(200).send('Stat % reg folders clear ');
});
let sss = "";
// получение данных для графика
exports.get_data = functions.https.onCall((data, context) => {
  const dev=data.dev;
  var width=data.width;
  const uid = context.auth.uid;
  const mode= data.mode?data.mode:0;
  /*
  0- год
  1- месяц
  2- неделя
  3- день
  */
  console.log(dev, uid, width, mode);
  let ref = db.ref(`devices-telemetry/${dev}/graph`).orderByChild("tm");
  var D= new Date();
  switch (mode){
    case 0:   D.setFullYear(D.getFullYear() -1);
    break;
    case 1: D.setMonth(D.getMonth() -1);
    break;
    case 2: D.setDate(D.getDate() -7);
    break;
    case 3: D.setDate(D.getDate() -1);
    break;    
  }
  var strAt= D.toISOString();
  console.log(strAt);
  return ref.startAt(strAt).once("value")
    .then((val) => {
      const graph = val.toJSON();
      var arrCo2 = [];
      var arrT = [];
      var arrH = [];
      var len=Object.keys(graph).length;
      console.log("len", len);
      width=(len>width)?width:len;
//      if (len>width){
        Object.keys(graph).forEach(el => {
          arrCo2.push([new Date(graph[el].tm), graph[el].co2]);
          arrT.push([new Date(graph[el].tm), graph[el].t]);
          arrH.push([new Date(graph[el].tm), graph[el].h]);
        });
        return {
          t:ASAP(arrT, width),
          h:ASAP(arrH, width),
          co2:ASAP(arrCo2, width),
        };   
/*      }else{
        Object.keys(graph).forEach(el => {
          arrCo2.push({x: new Date(graph[el].tm), y: graph[el].co2});
          arrT.push({x: new Date(graph[el].tm), y: graph[el].t});
          arrH.push({x: new Date(graph[el].tm), y: graph[el].h});

        });
        return {
          t:arrT,
          h:arrH,
          co2:arrCo2
        };         
      }*/


    });

});

function prepareSMA(arr , len){
  const smooth = SMA(arr, len);
  var outArr = [];
  smooth.forEach((s) => {
    outArr.push([s.x, s.y]);
  });
  return outArr;

}


// коллбэк функция авторизации, получает и сохраняет токен
exports.oauthcallback = functions.https.onRequest(async (req, res) => {
  console.log(`oauthcallback_start`);
  res.set('Cache-Control', 'private, max-age=0, s-maxage=0');
  const code = req.query.code;
  console.log(`oauthcallback: ${code}`);
  try {
    const { tokens } = await functionsOauthClient.getToken(code);
    console.log(`oauthcallback_token: ${tokens}`);
    // сохраненеи токена авторизации
    await db.ref(DB_TOKEN_PATH).set(tokens);
    return res.status(200).send('App successfully configured with new Credentials. '
      + 'You can now close this page.');
  } catch (error) {
    return res.status(400).send(error);
  }
});

let oauthTokens = null;
// проверка и подготовка данных авторизации
async function getAuthorizedClient() {
  if (oauthTokens) {
    return functionsOauthClient;
  }
  const snapshot = await db.ref(DB_TOKEN_PATH).once('value');
  oauthTokens = snapshot.val();
  functionsOauthClient.setCredentials(oauthTokens);
  return functionsOauthClient;
}

let dataNew = {}, data = {}, val = {}, ref = {}, config = {};
let deviceId = "", s, t, str, saveData = false;
let reqConfig = false;
var gpLen;

// чтение конфига
function getConfig() {
  return ref.child("config").once('value')
    .then((snapshotConf) => {
      config = snapshotConf.toJSON();
      console.log("config", config);
    });
}


//сохранение показаний контроллера
function saveLast() {
  return ref.child("last").set(data);
}
//сохранение показаний контроллера для графика
function saveGraph(d) {
  return ref.child("graphPrep").push(d);
}
//сохранение статистики
function saveStat() {
  var stat = typeof config.stat === "undefined" ? 1 : config.stat;
  if (stat == 1 && saveData) {
    return db.ref(`devices-telemetry/${deviceId}/stat/${str}`).set({ "d": dataNew, "s": data });
  } else {
    return true;
  }
}
//сохранение статистики
function saveReg(s) {
  var stat = (typeof config.stat === "undefined") ? 1 : config.stat;
  if (stat == 1) {
    return db.ref(`reg`).push(s);
  } else {
    return true;
  }
}

// обработка событий registry
exports.detectRegEvents = functions.pubsub.topic('registry-topic')
  .onPublish((message, context) => {
    const a = message.json.mem.toFixed();
    const b = message.json.uptime.toFixed();
    const t = context.timestamp
    const s = { t: t, m: a, u: b };
    deviceId = message.attributes.deviceId;
    ref = db.ref(`devices-telemetry/${deviceId}`);
    return Promise.all([
      getConfig()
    ])
      .then(() => {
        return saveReg(s);
      })
  });

// обработка получения данных от контроллера
exports.detectTelemetryEvents = functions.pubsub.topic('main-telemetry-topic')
  .onPublish((message, context) => {
    const a = message.json.a;
    const b = message.json.b;
    const c = message.json.c;
    const d = message.json.d;
    deviceId = message.attributes.deviceId;
    const timestamp = context.timestamp;

    data = {
      tm: timestamp,
      t: a,
      h: b,
      co2: c,

    };
    if (d > 0) {
      reqConfig = true;
      console.log('request for config');
    } else {
      reqConfig = false;
    }
    ref = db.ref(`devices-telemetry/${deviceId}`);

    return Promise.all([
      saveLast(),
      ConfigPromise(deviceId),
      getConfig()
    ])
      .then(() => {
        return ref.child("graphPrep").once('value');

      })
      .then((gp) => {
        try {
          gpLen = typeof config.gpLen === "undefined" ? 3 : config.gpLen;
          const graphPrep = gp.toJSON();
          if (typeof graphPrep !== "undefined"
            && graphPrep !== null
            && Object.keys(graphPrep).length >= gpLen) {
            graphPrep.xxxx = data;
            return addGraphElement(graphPrep);
          } else {
            return saveGraph(data);
          }
        } catch (error) {
          console.log(error);
          return saveGraph(data);
        }

      });
  });

function addGraphElement(graphPrep) {
  var t = 0, h = 0, co2 = 0, tm, k = 0;
  Object.keys(graphPrep).forEach(element => {
    t += graphPrep[element].t;
    h += graphPrep[element].h;
    co2 += graphPrep[element].co2;
    tm = graphPrep[element].tm;
    k++;
  });
  const graphData = {};
  graphData.t = t / k;
  graphData.h = h / k;
  graphData.co2 = co2 / k;
  graphData.tm = tm;
  return ref.child("graph").push(graphData)
    .then(() => {
      return ref.child("graphPrep").remove();
    });

}

// проверка необходимости отправки письма
function checkEmail() {
  var dt = new Date();
  var month = dt.getMonth();
  var day = dt.getDate();
  config['m'] = (typeof config['m'] == "undefined") ? month : config['m']; //создать параметр
  if (day >= config['day'] && month == config['m']) {
    month++;
    if (month == 12) {
      month = 0;
    }
    console.log(`emailStart`);
    var year = dt.getFullYear();
    db.ref(`devices-telemetry/${deviceId}/config/m`).set(month);
    db.ref(`devices-telemetry/${deviceId}/config/y`).set(year);
    //сохранение данных за предыдущий месяц
    db.ref(`devices-telemetry/${deviceId}/res/${year}-${dt.getMonth()}`).set(data);
    return sendemail(data.a.toFixed(2), data.b.toFixed(2), data.c.toFixed(2), data.t);
  }
  return true;
}
//отправка письма
function sendemail(a, b, f, dt) {
  return getAuthorizedClient()
    .then((auth) => {
      try {
        var Mail = require('./createMail.js');
        var mess = `<h1>Привет</h1><p>Показания счетчиков на сегодня<br>Хол. вода: ${a} <br>Гор. вода: ${b} <br>Фильтр: ${f} <br></p><p>Ваши счетчики</p>`;
        var obj = new Mail(auth, functions.config().googleapi.mailto, `Показания счетчиков ${dt}`, mess, 'mail');
        // отправка письма
        obj.makeBody();
        console.log(`emailSend`);
        return true;
      } catch (error) {
        console.log(`emailprocessError`, error);
        return false;
      }
    });
}


function ConfigPromise(device) {

  return new Promise((resolve, reject) => {
    var curConf = 0;
    var sysCo2 = 0;
    return ref.child("co2").once('value')
      .then((v) => {
        curConf = v.val();
        return db.ref(`config/co2`).once('value');
      })
      .then((v) => {
        sysCo2 = v.val();
        return ((sysCo2 == curConf) && !reqConfig);
      })
      .then((v) => {
        if (v) {
          console.log('config not  changed');
          console.log(`conf ${curConf} cur ${sysCo2}`);
          return resolve(true);;
        } else {
          return getAuthorizedClient()
            .then((client) => {
              google.options({
                auth: client
              });

              const config = {
                co2: sysCo2
              };
              return ref.child("co2").set(sysCo2)
                .then(() => {
                  console.log('START setDeviceConfig');
                  const parentName = `projects/${process.env.GCLOUD_PROJECT}/locations/europe-west1`;
                  const registryName = `${parentName}/registries/co2-devices-registry`;
                  const binaryData = Buffer.from(JSON.stringify(config)).toString('base64');
                  const request = {
                    name: `${registryName}/devices/${device}`,
                    versionToUpdate: 0,
                    binaryData: binaryData
                  };
                  return google.cloudiot('v1').projects.locations.registries.devices.modifyCloudToDeviceConfig(request, (err, response) => {
                    if (err) {
                      console.log(`The API returned an error: ${err}`);
                      return reject(err);
                    }
                    console.log("setDeviceConfig finished");
                    return resolve(response.data);
                  });
                });
            });
        }

      });
  });
}