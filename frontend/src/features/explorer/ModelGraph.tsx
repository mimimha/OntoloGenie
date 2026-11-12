import {
  BaseEdge,
  EdgeLabelRenderer,
  getBezierPath,
  Handle,
  MarkerType,
  Panel,
  Position,
  ReactFlow,
  type Edge,
  type EdgeProps,
  type Node,
  type NodeProps,
  type ReactFlowInstance,
} from '@xyflow/react'
import { useRef } from 'react'
import Button from '../../components/Button'
import { formatCount } from './format'
import '@xyflow/react/dist/style.css'
import {
  groupRelationLines,
  layoutTypeGraph,
  selfRelationHeight,
  TYPE_NODE_HEIGHT,
  TYPE_NODE_WIDTH,
} from './modelGraphLayout'
import type {
  ModelEdge,
  ModelGraph as ModelGraphData,
  ModelNode,
} from './types'

type TypeNodeData = {
  type: ModelNode
  selected: boolean
  onSelect: (id: number) => void
  loopHeight: number
}

type TypeFlowNode = Node<TypeNodeData, 'type'>

// 유형 카드. 선이 붙을 자리(Handle)는 보이지 않게 두고, 카드 자체는 누를 수 있는 버튼이다.
function TypeNode({ data }: NodeProps<TypeFlowNode>) {
  const { type, selected, onSelect, loopHeight } = data

  return (
    <div style={{ paddingTop: loopHeight }}>
      <Handle
        type="target"
        position={Position.Left}
        isConnectable={false}
        style={{ opacity: 0, top: loopHeight + TYPE_NODE_HEIGHT / 2 }}
      />
      <button
        type="button"
        aria-pressed={selected}
        // 화면에서는 유형 이름만 보이지만, 화면 낭독기에는 무엇의 이름인지 함께 알린다.
        aria-label={`객체 유형 ${type.name}`}
        onClick={() => onSelect(type.id)}
        // nopan·nodrag: 카드를 누를 때 관계도 끌어서 이동이 같이 시작되지 않게 한다.
        className={`model-graph-node nopan nodrag border px-2 py-2 text-center ${
          selected
            ? 'border-slate-900 bg-white text-slate-900'
            : 'border-slate-300 bg-white text-slate-900 hover:border-slate-900 disabled:border-dashed disabled:bg-slate-50 disabled:text-slate-400 disabled:hover:border-slate-300'
        }`}
        // 누를 수 없는 노드는 React Flow가 pointer-events를 끄기 때문에, 카드 버튼만 다시 켠다.
        style={{
          width: TYPE_NODE_WIDTH,
          height: TYPE_NODE_HEIGHT,
          pointerEvents: 'all',
        }}
      >
        <strong className="block">{type.name}</strong>
      </button>
      <Handle
        type="source"
        position={Position.Right}
        isConnectable={false}
        style={{ opacity: 0, top: loopHeight + TYPE_NODE_HEIGHT / 2 }}
      />
    </div>
  )
}

type RelationEdgeData = {
  edges: ModelEdge[]
  loopHeight: number
}

type RelationFlowEdge = Edge<RelationEdgeData, 'relation'>

// 관계 선. 같은 출발·도착 유형 사이의 관계 이름을 선 가운데에 줄을 나눠 적는다.
function RelationEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  style,
  data,
}: EdgeProps<RelationFlowEdge>) {
  let [path, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
  })
  if (data?.loopHeight) {
    labelX = (sourceX + targetX) / 2
    labelY = sourceY - TYPE_NODE_HEIGHT / 2 - data.loopHeight / 2
    path = `M ${sourceX} ${sourceY} C ${sourceX + 24} ${sourceY}, ${sourceX + 24} ${labelY}, ${labelX} ${labelY} C ${targetX - 24} ${labelY}, ${targetX - 24} ${targetY}, ${targetX} ${targetY}`
  }

  return (
    <>
      <BaseEdge id={id} path={path} markerEnd={markerEnd} style={style} />
      <EdgeLabelRenderer>
        <div
          className="absolute rounded bg-white/90 px-1 text-center text-xs leading-tight whitespace-nowrap text-slate-600"
          style={{
            transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
          }}
        >
          {data?.edges.map((edge) => (
            <span key={edge.id} className="block">
              {`${edge.name} · ${formatCount(edge.activeLinkCount)}건`}
            </span>
          ))}
        </div>
      </EdgeLabelRenderer>
    </>
  )
}

const nodeTypes = { type: TypeNode }
const edgeTypes = { relation: RelationEdge }

// 개요(관계도 그림) 또는 유형 목록. 고르는 버튼은 관계도 칸 제목 줄에 있다.
export type ModelGraphView = 'graph' | 'list'

type ModelGraphProps = {
  view: ModelGraphView
  graph: ModelGraphData
  selectedTypeId: number | null
  onSelect: (id: number) => void
}

function ModelGraph({
  view,
  graph,
  selectedTypeId,
  onSelect,
}: ModelGraphProps) {
  const flow = useRef<ReactFlowInstance<TypeFlowNode, RelationFlowEdge> | null>(
    null,
  )
  const lines = groupRelationLines(graph.edges)
  const points = layoutTypeGraph(graph.nodes, lines)

  // 카드 크기와 선이 붙을 자리를 미리 알려 주면, 화면에서 크기를 재기 전에도 선을 그릴 수 있다.
  const nodes: TypeFlowNode[] = graph.nodes.map((type) => ({
    id: String(type.id),
    type: 'type',
    position: points.get(type.id) ?? { x: 0, y: 0 },
    width: TYPE_NODE_WIDTH,
    height: TYPE_NODE_HEIGHT + selfRelationHeight(lines, type.id),
    handles: [
      {
        type: 'target',
        position: Position.Left,
        x: 0,
        y: selfRelationHeight(lines, type.id) + TYPE_NODE_HEIGHT / 2,
        width: 1,
        height: 1,
      },
      {
        type: 'source',
        position: Position.Right,
        x: TYPE_NODE_WIDTH,
        y: selfRelationHeight(lines, type.id) + TYPE_NODE_HEIGHT / 2,
        width: 1,
        height: 1,
      },
    ],
    data: {
      type,
      selected: type.id === selectedTypeId,
      onSelect,
      loopHeight: selfRelationHeight(lines, type.id),
    },
  }))

  const edges: RelationFlowEdge[] = lines.map((line) => ({
    id: line.key,
    type: 'relation',
    source: String(line.sourceTypeId),
    target: String(line.targetTypeId),
    markerEnd: { type: MarkerType.ArrowClosed, color: '#94a3b8' },
    style: {
      stroke: '#94a3b8',
      strokeWidth: 1.5,
      strokeDasharray: line.edges.every((edge) => edge.activeLinkCount === 0)
        ? '4 4'
        : undefined,
    },
    data: {
      edges: line.edges,
      loopHeight:
        line.sourceTypeId === line.targetTypeId
          ? selfRelationHeight(lines, line.sourceTypeId)
          : 0,
    },
  }))

  return (
    <div>
      {view === 'list' ? (
        <div className="model-graph-directory" aria-label="객체 유형 목록">
          {graph.nodes.map((type) => (
            <Button
              key={type.id}
              variant="secondary"
              aria-pressed={type.id === selectedTypeId}
              onClick={() => onSelect(type.id)}
            >
              {type.name}
            </Button>
          ))}
        </div>
      ) : (
        <>
          <div className="explorer-graph-canvas">
            <ReactFlow
              nodes={nodes}
              edges={edges}
              nodeTypes={nodeTypes}
              edgeTypes={edgeTypes}
              onInit={(instance) => {
                flow.current = instance
              }}
              fitView
              fitViewOptions={{ padding: 0.15 }}
              minZoom={0.3}
              maxZoom={1.5}
              nodesDraggable={false}
              nodesConnectable={false}
              elementsSelectable={false}
              proOptions={{ hideAttribution: true }}
            >
              {/* 배율 버튼은 그림 위 오른쪽에 겹쳐 두어 그림 위에 버튼 줄이 생기지 않게 한다. */}
              <Panel position="top-right">
                <div
                  role="group"
                  aria-label="관계도 배율 조절"
                  className="model-graph-zoom"
                >
                  <button
                    type="button"
                    onClick={() => void flow.current?.zoomTo(1)}
                  >
                    읽기 크기
                  </button>
                  <button
                    type="button"
                    onClick={() =>
                      void flow.current?.fitView({ padding: 0.15 })
                    }
                  >
                    전체 맞춤
                  </button>
                  <button
                    type="button"
                    onClick={() => void flow.current?.zoomIn()}
                    aria-label="관계도 확대"
                  >
                    ＋
                  </button>
                  <button
                    type="button"
                    onClick={() => void flow.current?.zoomOut()}
                    aria-label="관계도 축소"
                  >
                    −
                  </button>
                </div>
              </Panel>
            </ReactFlow>
          </div>
        </>
      )}

      {/* 건수와 범례를 한 줄에 두어 1440×900 첫 화면 안에 들어오게 한다. */}
      <p className="model-graph-caption">
        <span>
          {`객체 유형 ${graph.nodes.length}개 · 관계 유형 ${graph.edges.length}개`}
        </span>
        {view === 'graph' && (
          <span>점선: 현재 연결 0건 · 화살표: 관계 방향</span>
        )}
      </p>
    </div>
  )
}

export default ModelGraph
