import * as React from 'react';

import { RNNodeJsMobileViewProps } from './RNNodeJsMobile.types';

export default function RNNodeJsMobileView(props: RNNodeJsMobileViewProps) {
  return (
    <div>
      <iframe
        style={{ flex: 1 }}
        src={props.url}
        onLoad={() => props.onLoad({ nativeEvent: { url: props.url } })}
      />
    </div>
  );
}
