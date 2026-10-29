import type { Row } from '../api/client'
import { Card, DataTable, KeyValues } from './ui'

/** Renders an arbitrary API map: scalar fields as key/values, nested objects and lists as cards. */
export function Sections({ data, skip = [] }: { data: Row | null | undefined; skip?: string[] }) {
  if (!data) return null
  const keys = Object.keys(data).filter((k) => !skip.includes(k))
  const scalars = keys.filter((k) => data[k] === null || typeof data[k] !== 'object')
  const objects = keys.filter((k) => data[k] !== null && typeof data[k] === 'object')
  return (
    <>
      {scalars.length > 0 && <Card><KeyValues data={data} keys={scalars} /></Card>}
      {objects.map((k) => {
        const v = data[k]
        const title = k.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/^./, (c) => c.toUpperCase())
        if (Array.isArray(v)) {
          return (
            <Card key={k} title={`${title} (${v.length})`}>
              {v.length > 0 && typeof v[0] !== 'object'
                ? <p>{v.map(String).join(', ')}</p>
                : <DataTable rows={v as Row[]} />}
            </Card>
          )
        }
        return <Card key={k} title={title}><KeyValues data={v as Row} /></Card>
      })}
    </>
  )
}
