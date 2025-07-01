package mill.api

/**
 * A module which you can override [[moduleDirectChildren]] to dynamically
 * enable or disable child modules at runtime
 */
trait DynamicModule extends Module
