import { useApp } from "@beavent/native";
import { price, type SearchPin } from "@beavent/venue";
import { AppleMaps, GoogleMaps } from "expo-maps";
import { useEffect, useRef } from "react";
import { Platform, StyleSheet } from "react-native";

export interface MapWindow {
  west: number;
  south: number;
  east: number;
  north: number;
}

type Camera = { coordinates: { latitude: number; longitude: number }; zoom: number };

/** Roughly the zoom level that shows `span` degrees across a phone's width, with some room around. */
const zoomFor = (span: number) =>
  Math.max(3, Math.min(16, Math.floor(Math.log2(360 / (span * 1.6)))));

/** The camera that shows all pins (or Addis Ababa when there are none). */
export function fitPins(pins: SearchPin[]): Camera {
  if (pins.length === 0) return { coordinates: { latitude: 9.0108, longitude: 38.7578 }, zoom: 11 };
  const lat = pins.map((p) => p.latitude);
  const lng = pins.map((p) => p.longitude);
  const [south, north] = [Math.min(...lat), Math.max(...lat)];
  const [west, east] = [Math.min(...lng), Math.max(...lng)];
  return {
    coordinates: { latitude: (south + north) / 2, longitude: (west + east) / 2 },
    zoom: zoomFor(Math.max(north - south, east - west, 0.02)),
  };
}

/**
 * The places on a native map (S-03): Apple Maps on iOS, Google Maps on Android. Each place is a pill with its "from"
 * price on iOS (a marker with the price as its title on Android); the chosen place is highlighted. `onMoved` reports
 * what the map shows once a person has moved it away from where it was put, for "Search this area".
 */
export function PlacesMap({
  pins,
  selectedId,
  camera,
  onSelect,
  onMoved,
}: {
  pins: SearchPin[];
  selectedId: string | undefined;
  /** Where to look; a new object moves the map there. */
  camera: Camera;
  onSelect: (id: string) => void;
  onMoved: (view: MapWindow) => void;
}) {
  const { colors, language, t } = useApp();
  const target = useRef(camera);
  useEffect(() => {
    target.current = camera;
  }, [camera]);

  const label = (pin: SearchPin) =>
    pin.priceFromMinor === undefined || !pin.currency
      ? pin.title
      : pin.priceFromMinor === 0
        ? t("venue.free")
        : price(pin.priceFromMinor, pin.currency, language);

  const reportMove = (event: {
    coordinates: { latitude?: number; longitude?: number };
    zoom: number;
    latitudeDelta: number;
    longitudeDelta: number;
  }) => {
    const { latitude = 0, longitude = 0 } = event.coordinates;
    const was = target.current;
    const moved =
      Math.abs(latitude - was.coordinates.latitude) > event.latitudeDelta * 0.15 ||
      Math.abs(longitude - was.coordinates.longitude) > event.longitudeDelta * 0.15 ||
      Math.abs(event.zoom - was.zoom) > 0.7;
    if (moved)
      onMoved({
        west: longitude - event.longitudeDelta / 2,
        east: longitude + event.longitudeDelta / 2,
        south: latitude - event.latitudeDelta / 2,
        north: latitude + event.latitudeDelta / 2,
      });
  };

  if (Platform.OS === "ios") {
    return (
      <AppleMaps.View
        style={StyleSheet.absoluteFill}
        cameraPosition={camera}
        uiSettings={{ myLocationButtonEnabled: false }}
        annotations={pins.map((pin) => ({
          id: pin.id,
          coordinates: { latitude: pin.latitude, longitude: pin.longitude },
          text: label(pin),
          textColor: pin.id === selectedId ? colors.onPrimary : colors.text,
          backgroundColor: pin.id === selectedId ? colors.primary : colors.background,
          title: pin.title,
        }))}
        onAnnotationClick={(event) => event.id && onSelect(event.id)}
        onCameraMove={reportMove}
      />
    );
  }
  return (
    <GoogleMaps.View
      style={StyleSheet.absoluteFill}
      cameraPosition={camera}
      uiSettings={{ myLocationButtonEnabled: false }}
      markers={pins.map((pin) => ({
        id: pin.id,
        coordinates: { latitude: pin.latitude, longitude: pin.longitude },
        title: pin.title,
        snippet: label(pin),
      }))}
      onMarkerClick={(event) => event.id && onSelect(event.id)}
      onCameraMove={reportMove}
    />
  );
}
