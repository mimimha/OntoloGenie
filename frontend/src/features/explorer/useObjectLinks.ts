import { useInfiniteQuery } from '@tanstack/react-query'
import { fetchObjectLinks, type LinkGroupFilter } from './api'

// 078을 쪽 단위로 이어 불러온다. filter가 없으면 근거 팝업이 쓰는 전체 목록이고,
// 있으면 관계도의 묶음 하나(관계 유형·방향)만 받는다.
export function useObjectLinks(objectId: number, filter?: LinkGroupFilter) {
  const query = useInfiniteQuery({
    queryKey: filter
      ? ['objects', objectId, 'links', filter.linkTypeId, filter.direction]
      : ['objects', objectId, 'links'],
    queryFn: ({ pageParam }) => fetchObjectLinks(objectId, pageParam, filter),
    initialPageParam: 0,
    getNextPageParam: (lastPage) =>
      lastPage.meta?.hasNext ? lastPage.meta.page + 1 : undefined,
  })

  const pages = query.data?.pages ?? []
  // 쪽을 나눠 받는 사이 앞쪽 연결이 늘면 같은 연결이 다음 쪽에 다시 올 수 있다.
  const seen = new Set<number>()
  const links = pages
    .flatMap((page) => page.data)
    .filter((link) => {
      if (seen.has(link.id)) {
        return false
      }
      seen.add(link.id)
      return true
    })
  // 건수는 가장 최근에 받은 쪽 기준이다. 다 받았으면 받은 연결 수가 곧 전체다.
  const latestTotal = pages[pages.length - 1]?.meta?.totalElements
  const totalCount = query.hasNextPage
    ? Math.max(latestTotal ?? 0, links.length)
    : links.length

  return { ...query, links, totalCount, latestTotal }
}
