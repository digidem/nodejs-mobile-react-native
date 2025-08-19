import { requireNativeView } from 'expo';
import * as React from 'react';

import { RNNodeJsMobileViewProps } from './RNNodeJsMobile.types';

const NativeView: React.ComponentType<RNNodeJsMobileViewProps> =
  requireNativeView('RNNodeJsMobile');

export default function RNNodeJsMobileView(props: RNNodeJsMobileViewProps) {
  return <NativeView {...props} />;
}
