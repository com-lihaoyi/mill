package mill.scalajslib.api

import upickle.ReadWriter

enum ESVersion derives ReadWriter {
  case ES2015
  case ES2016
  case ES2017
  case ES2018
  case ES2019
  case ES2020
  case ES2021
  case ES5_1
}
