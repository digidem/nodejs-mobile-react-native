import { NativeModule, requireNativeModule } from 'expo';

import { RNNodeJsMobileModuleEvents } from './RNNodeJsMobile.types';

declare class RNNodeJsMobileModule extends NativeModule<RNNodeJsMobileModuleEvents> {
  PI: number;
  hello(): string;
  setValueAsync(value: string): Promise<void>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<RNNodeJsMobileModule>('RNNodeJsMobile');
