package mill.androidlib
import upickle.*

/**
 * Android Virtual Device configuration
 *
 * For more information on available devices and images,
 * run `avdmanager list` and `sdkmanager --list`
 * @param name Identification name of the AVD
 * @param apiVersion e.g. "android-33"
 * @param architecture e.g. "x86_64"
 * @param deviceId e.g. "medium_phone"
 * @param systemImageSource e.g. "google_apis_playstore"
 */
case class AndroidVirtualDevice(
    name: String,
    apiVersion: String,
    architecture: String,
    deviceId: String,
    systemImageSource: String
) {
  val systemImage: String = s"system-images;${systemImageSource};${apiVersion};${architecture}"
}

object AndroidVirtualDevice {

  def apply(
      apiVersion: String,
      architecture: String,
      deviceId: String,
      systemImageSource: String
  ): AndroidVirtualDevice = {
    val name: String = s"mill_avd_${apiVersion}_${architecture}_${deviceId}"
    AndroidVirtualDevice(
      name,
      apiVersion,
      architecture,
      deviceId,
      systemImageSource
    )
  }

  implicit val rw: ReadWriter[AndroidVirtualDevice] = macroRW
}
