export type Data = Record<string, unknown>;
export interface Transaction {
  get(path: string): Promise<Data | undefined>;
  set(path: string, data: Data, merge?: boolean): void;
  create(path: string, data: Data): void;
}
export interface Storage {
  transaction<T>(work: (tx: Transaction) => Promise<T>): Promise<T>;
}
