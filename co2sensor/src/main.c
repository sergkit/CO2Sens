#include <stdio.h>

//#include "mgos_rpc.h"

#include "common/cs_dbg.h"
#include "common/json_utils.h"
#include "common/platform.h"
#include "common/mg_str.h"
#include "frozen.h"

#include "mgos.h"
#include "mgos_app.h"
#include "mgos_gpio.h"
#include "mgos_timers.h"
//#include "mgos_mqtt.h"
#include "mgos_config.h"
#include "mgos_dht.h"
#include "mgos_gcp.h"

#define deb_mode 1
#ifdef deb_mode
#define LOG_(l, x)                                      \
    do                                                  \
    {                                                   \
        if (cs_log_print_prefix(l, __FILE__, __LINE__)) \
        {                                               \
            cs_log_printf x;                            \
        }                                               \
    } while (0)
#else
#define LOG_(l, x)
#endif

int wdPin, dthPin, pwmPin, gLed, rLed;
bool connected = false, isGood = true;
uint8_t getConfig = 1;

static struct mgos_dht *s_dht = NULL;
/*// static struct mgos_gcp_config_arg *msg=NULL;
struct rrr
{
    int len;
    int p;
};
struct conf
{
    struct rrr value;
};

//static struct conf *msg1 = NULL;
*/
static void wd_update()
{
    mgos_gpio_setup_output(wdPin, true);
    mgos_usleep(1);
    mgos_gpio_setup_output(wdPin, false);
}

static inline uint64_t uptime()
{
    return (uint64_t)(1000000 * mgos_uptime());
}

uint32_t pulseInLongLocal(uint8_t pin, uint8_t state, uint32_t timeout)
{
    uint64_t startMicros = uptime();

    // wait for any previous pulse to end
    while (state == mgos_gpio_read(pin))
    {
        if ((uptime() - startMicros) > timeout)
        {
            return 0;
        }
    }

    // wait for the pulse to start
    while (state != mgos_gpio_read(pin))
    {
        if ((uptime() - startMicros) > timeout)
        {
            return 0;
        }
    }

    uint64_t start = uptime();

    // wait for the pulse to stop
    while (state == mgos_gpio_read(pin))
    {
        if ((uptime() - start) > timeout)
        {
            return 0;
        }
    }
    uint32_t th = (uint32_t)(uptime() - start - 2000);
    while (state != mgos_gpio_read(pin))
    {
        if ((uptime() - start) > timeout)
        {
            return 0;
        }
    }
    uint32_t th_tl = (uint32_t)(uptime() - start - 4000);
    return (uint32_t)(5000 * th / th_tl);
}

static void set_default(){
    if(mgos_config_get_http_enable(&mgos_sys_config)){
        mgos_config_set_http_enable(&mgos_sys_config,false);
        mgos_config_set_wifi_ap_enable(&mgos_sys_config, false);
        mgos_sys_config_save(&mgos_sys_config, false, NULL);
        mgos_system_restart_after(100);
    }
}

static void my_net_ev_handler(int ev, void *evd, void *arg)
{
    if (ev == MGOS_NET_EV_IP_ACQUIRED)
    {
        LOG_(LL_INFO, ("Connected+++++!"));
        connected = true;
        set_default();
    }
    else
    {
        LOG_(LL_INFO, ("Connected------!"));
        connected = false;
    }
    wd_update();
    (void)evd;
    (void)arg;
}

static void my_gcp_ev_handler(int ev, void *evd, void *arg)
{
    if (ev == MGOS_GCP_EV_CONFIG)
    {
        LOG_(LL_INFO, ("MGOS_GCP_EV_CONFIG"));
        struct mgos_gcp_config_arg *msg = (struct mgos_gcp_config_arg *)evd;
        struct mg_str *s = &msg->value;
        LOG_(LL_INFO, ("got command: [%.*s]", (int)s->len, s->p));
        int co2 = 0;
        if (json_scanf(s->p, s->len, "{co2:  %d}", &co2) == 1)
        {
            mgos_sys_config_set_app_co2_level(co2);
            mgos_sys_config_save(&mgos_sys_config, false, NULL);
        }
    }
    if (ev == MGOS_GCP_EV_COMMAND)
    {
        LOG_(LL_INFO, ("MGOS_GCP_EV_CONFIG"));
    }
    (void)evd;
    (void)arg;
}

static void setLed(uint32_t co2)
{
    uint32_t confCO2 = (uint32_t)mgos_sys_config_get_app_co2_level();
    bool newState = (co2 < confCO2);
    LOG_(LL_INFO, ("co2: %d conf: %d  st: %d", co2, confCO2, (int)newState));
    if (newState != isGood)
    {
        isGood = newState;
        mgos_gpio_setup_output(gLed, false);
        mgos_gpio_setup_output(rLed, false);
        if (isGood)
        {
            mgos_gpio_setup_output(gLed, true);
        }
        else
        {
            mgos_gpio_setup_output(rLed, true);
        }
    }
}

static void read_send_timer(void *arg)
{
    float t = mgos_dht_get_temp(s_dht);
    float h = mgos_dht_get_humidity(s_dht);
    int heap, up;
    heap = (int)mgos_get_free_heap_size();
    up = (int)mgos_uptime();

    if (isnan(h) || isnan(t))
    {
        LOG_(LL_INFO, ("Failed to read data from sensor\n"));
        return;
    }
    unsigned long co2 = pulseInLongLocal(pwmPin, 1, mgos_sys_config_get_app_pulse_in_timeout_usecs());

    LOG_(LL_INFO, ("{a:%f,b:%f,c:%lu,d:%d}", t, h, co2, getConfig));
    LOG_(LL_INFO, ("{mem:%d,uptime:%d}", heap, up));

    if (connected && mgos_gcp_is_connected())
    {
        if (mgos_gcp_send_eventf("{a:%f,b:%f,c:%lu,d:%d}", t, h, co2, getConfig))
        {
            LOG_(LL_INFO, ("GCP send"));
            getConfig = 0;
        }
    }
    setLed((uint32_t)co2);
    (void)arg;
}

static void timer_state(void *arg)
{
    int heap, up;
    heap = (int)mgos_get_free_heap_size();
    up = (int)mgos_uptime();
    LOG_(LL_INFO, ("{mem:%d,uptime:%d}", heap, up));
    if (connected && mgos_gcp_is_connected())
    {
        if (mgos_gcp_send_event_subf(mgos_sys_config_get_app_status_folder(), "{mem:%d,uptime:%d}", heap, up))
        {
            LOG_(LL_INFO, ("GCP state send"));
        }
    }

    if (heap < mgos_sys_config_get_app_minram())
    {
        LOG_(LL_INFO, ("LOW MEM RESTART"));
        mgos_system_restart_after(500);
    }
    (void)arg;
}

static void wd_timer(void *arg)
{

    wd_update();
    (void)arg;
}

// as pulseIn isn't supported in Mongoose Arduino compatability library yet, here's a local
// implementation of that. Full credit to "nliviu" on Mongoose OS forums for that
// https://forum.mongoose-os.com/discussion/1928/arduino-compat-lib-implicit-declaration-of-function-pulsein#latest

enum mgos_app_init_result mgos_app_init(void)
{
    /*   enum mgos_gpio_pull_type btn_pull;
  enum mgos_gpio_int_mode btn_int_edge;
  btn_pull = MGOS_GPIO_PULL_NONE;
  btn_int_edge = MGOS_GPIO_INT_EDGE_NEG;*/
    // set the modes for the pins

    // mgos_gpio_set_mode(mgos_sys_config_get_app_gpio_trigger_pin(), MGOS_GPIO_MODE_OUTPUT);
    wdPin = mgos_sys_config_get_app_WDDonePin();
    dthPin = mgos_sys_config_get_app_gpio_dth_pin();
    pwmPin = mgos_sys_config_get_app_gpio_pwm_pin();
    gLed = mgos_sys_config_get_app_gpio_green_pin();
    rLed = mgos_sys_config_get_app_gpio_red_pin();

    mgos_gpio_set_mode(wdPin, MGOS_GPIO_MODE_OUTPUT);
    mgos_gpio_setup_output(wdPin, false);

    mgos_gpio_set_mode(gLed, MGOS_GPIO_MODE_OUTPUT);
    mgos_gpio_setup_output(gLed, true);
    mgos_gpio_set_mode(rLed, MGOS_GPIO_MODE_OUTPUT);
    mgos_gpio_setup_output(rLed, false);

    wd_update();
    mgos_gpio_set_mode(dthPin, MGOS_GPIO_MODE_INPUT);
    mgos_gpio_set_pull(dthPin, MGOS_GPIO_PULL_NONE);
    mgos_gpio_set_mode(pwmPin, MGOS_GPIO_MODE_INPUT);
    mgos_gpio_set_pull(pwmPin, MGOS_GPIO_PULL_NONE);

    if ((s_dht = mgos_dht_create(dthPin, DHT22)) == NULL)
        return MGOS_APP_INIT_ERROR;
    mgos_set_timer(mgos_sys_config_get_app_sensor_read_interval_ms(), true, read_send_timer, NULL);
    mgos_set_timer(mgos_sys_config_get_app_wdTimer(), MGOS_TIMER_REPEAT, wd_timer, NULL);

    //Отслеживание событий сети
    mgos_event_add_group_handler(MGOS_EVENT_GRP_NET, my_net_ev_handler, NULL);
    mgos_event_add_group_handler(MGOS_GCP_EV_BASE, my_gcp_ev_handler, NULL);

    //Таймер отправки состояния
    mgos_set_timer(mgos_sys_config_get_app_status_timer(), MGOS_TIMER_REPEAT, timer_state, NULL);

    return MGOS_APP_INIT_SUCCESS;
}