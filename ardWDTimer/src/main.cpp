#include <Arduino.h>

volatile bool res=true;
volatile bool state=true;
uint64_t cnt=30000;
const byte intPin=2;
const byte ledPin = 13;
const byte resPin = 4;
uint64_t tm=0;

void resFunction(){
  res=true;
  state=!state;
}

void setup() {
  pinMode(intPin, INPUT_PULLUP);
  pinMode(ledPin, OUTPUT);
  pinMode(resPin, OUTPUT);
  digitalWrite(resPin, HIGH);
  Serial.begin(9600);
  attachInterrupt(digitalPinToInterrupt(intPin), resFunction, RISING);
  tm=millis();
}



void loop() {
  if(millis()-tm>cnt){
    //reset  esp
    digitalWrite(resPin, LOW);
    Serial.println();
    Serial.println("RESET");
    delay(5);
    digitalWrite(resPin, HIGH);
    res=true;
  }
  if(res){
    res=false;
    digitalWrite(ledPin, state);
    tm=millis();
    Serial.print(".");
  }
  
}